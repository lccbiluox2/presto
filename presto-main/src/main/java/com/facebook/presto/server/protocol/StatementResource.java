/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.server.protocol;

import com.facebook.presto.client.QueryResults;
import com.facebook.presto.execution.QueryManager;
import com.facebook.presto.memory.context.SimpleLocalMemoryContext;
import com.facebook.presto.metadata.SessionPropertyManager;
import com.facebook.presto.operator.ExchangeClient;
import com.facebook.presto.operator.ExchangeClientSupplier;
import com.facebook.presto.server.ForStatementResource;
import com.facebook.presto.server.HttpRequestSessionContext;
import com.facebook.presto.server.SessionContext;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.block.BlockEncodingSerde;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.concurrent.BoundedExecutor;
import io.airlift.stats.CounterStat;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import org.weakref.jmx.Managed;
import org.weakref.jmx.Nested;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map.Entry;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;

import static com.facebook.presto.client.PrestoHeaders.PRESTO_ADDED_PREPARE;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_CLEAR_SESSION;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_CLEAR_TRANSACTION_ID;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_DEALLOCATED_PREPARE;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_SET_CATALOG;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_SET_PATH;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_SET_ROLE;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_SET_SCHEMA;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_SET_SESSION;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_STARTED_TRANSACTION_ID;
import static com.facebook.presto.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.net.HttpHeaders.X_FORWARDED_PROTO;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.concurrent.Threads.threadsNamed;
import static io.airlift.http.server.AsyncResponseHandler.bindAsyncResponse;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * //从这里可以看出，所有路径为/v1/statement的URL请求都交给该类提供的RESTful服务处理
 */
@Path("/v1/statement")
public class StatementResource
{
    private static final Duration MAX_WAIT_TIME = new Duration(1, SECONDS);
    private static final Ordering<Comparable<Duration>> WAIT_ORDERING = Ordering.natural().nullsLast();

    private static final DataSize DEFAULT_TARGET_RESULT_SIZE = new DataSize(1, MEGABYTE);
    private static final DataSize MAX_TARGET_RESULT_SIZE = new DataSize(128, MEGABYTE);

    private final QueryManager queryManager;
    private final SessionPropertyManager sessionPropertyManager;
    private final ExchangeClientSupplier exchangeClientSupplier;
    private final BlockEncodingSerde blockEncodingSerde;
    private final BoundedExecutor responseExecutor;
    private final ScheduledExecutorService timeoutExecutor;

    private final ConcurrentMap<QueryId, Query> queries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService queryPurger = newSingleThreadScheduledExecutor(threadsNamed("query-purger"));

    private final CounterStat createQueryRequests = new CounterStat();

    @Inject
    public StatementResource(
            QueryManager queryManager,
            SessionPropertyManager sessionPropertyManager,
            ExchangeClientSupplier exchangeClientSupplier,
            BlockEncodingSerde blockEncodingSerde,
            @ForStatementResource BoundedExecutor responseExecutor,
            @ForStatementResource ScheduledExecutorService timeoutExecutor)
    {
        this.queryManager = requireNonNull(queryManager, "queryManager is null");
        this.sessionPropertyManager = requireNonNull(sessionPropertyManager, "sessionPropertyManager is null");
        this.exchangeClientSupplier = requireNonNull(exchangeClientSupplier, "exchangeClientSupplier is null");
        this.blockEncodingSerde = requireNonNull(blockEncodingSerde, "blockEncodingSerde is null");
        this.responseExecutor = requireNonNull(responseExecutor, "responseExecutor is null");
        this.timeoutExecutor = requireNonNull(timeoutExecutor, "timeoutExecutor is null");

        queryPurger.scheduleWithFixedDelay(new PurgeQueriesRunnable(queries, queryManager), 200, 200, MILLISECONDS);
    }

    @PreDestroy
    public void stop()
    {
        queryPurger.shutdownNow();
    }

    /**
     * 地址为:/v1/statement的POST请求由以下方法处理
     *
     * @param statement
     * @param proto
     * @param servletRequest
     * @param uriInfo
     * @return
     */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response createQuery(
            String statement,
            @HeaderParam(X_FORWARDED_PROTO) String proto,
            @Context HttpServletRequest servletRequest,
            @Context UriInfo uriInfo)
    {
        createQueryRequests.update(1);

        if (isNullOrEmpty(statement)) {
            throw new WebApplicationException(Response
                    .status(Status.BAD_REQUEST)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("SQL statement is empty")
                    .build());
        }
        if (isNullOrEmpty(proto)) {
            proto = uriInfo.getRequestUri().getScheme();
        }

        SessionContext sessionContext = new HttpRequestSessionContext(servletRequest);

        ExchangeClient exchangeClient = exchangeClientSupplier.get(new SimpleLocalMemoryContext(newSimpleAggregatedMemoryContext(), StatementResource.class.getSimpleName()));

        // 在该方法中执行查询
        Query query = Query.create(
                sessionContext,
                statement,
                queryManager,
                sessionPropertyManager,
                exchangeClient,
                responseExecutor,
                timeoutExecutor,
                blockEncodingSerde);
        queries.put(query.getQueryId(), query);

        /**
         * 获得查询的结果并组装成Response进行返回，从该方法中可以看出第二个参数是空，若该参数为空，则分批返回结果
         */
        QueryResults queryResults = query.getNextResult(OptionalLong.empty(), uriInfo, proto, DEFAULT_TARGET_RESULT_SIZE);
        return toResponse(query, queryResults);
    }


    /**
     * //地址为:   /v1/statemen/queryID/token  的GET请求由以下方法处理，该方法根据Token值返回
     queryID对应的查询的部分执行结果，这里的Token主要用于保证分批读取查询结果的顺序。前面章节所说的
     客户端会不断向Coordinator获取部分的查询执行结果，直到获得了所有的结果。在这个过程中，每次获得部
     分查询执行结果的请求都是由该方法进行处理的。

     * @param queryId
     * @param token
     * @param maxWait
     * @param targetResultSize
     * @param proto
     * @param uriInfo
     * @param asyncResponse
     */
    @GET
    @Path("{queryId}/{token}")
    @Produces(MediaType.APPLICATION_JSON)
    public void getQueryResults(
            @PathParam("queryId") QueryId queryId,
            @PathParam("token") long token,
            @QueryParam("maxWait") Duration maxWait,
            @QueryParam("targetResultSize") DataSize targetResultSize,
            @HeaderParam(X_FORWARDED_PROTO) String proto,
            @Context UriInfo uriInfo,
            @Suspended AsyncResponse asyncResponse)
    {
        Query query = queries.get(queryId);
        if (query == null) {
            asyncResponse.resume(Response.status(Status.NOT_FOUND).build());
            return;
        }
        if (isNullOrEmpty(proto)) {
            proto = uriInfo.getRequestUri().getScheme();
        }

        asyncQueryResults(query, OptionalLong.of(token), maxWait, targetResultSize, uriInfo, proto, asyncResponse);
    }

    private void asyncQueryResults(
            Query query,
            OptionalLong token,
            Duration maxWait,
            DataSize targetResultSize,
            UriInfo uriInfo,
            String scheme,
            AsyncResponse asyncResponse)
    {
        Duration wait = WAIT_ORDERING.min(MAX_WAIT_TIME, maxWait);
        if (targetResultSize == null) {
            targetResultSize = DEFAULT_TARGET_RESULT_SIZE;
        }
        else {
            targetResultSize = Ordering.natural().min(targetResultSize, MAX_TARGET_RESULT_SIZE);
        }
        ListenableFuture<QueryResults> queryResultsFuture = query.waitForResults(token, uriInfo, scheme, wait, targetResultSize);

        ListenableFuture<Response> response = Futures.transform(queryResultsFuture, queryResults -> toResponse(query, queryResults), directExecutor());

        bindAsyncResponse(asyncResponse, response, responseExecutor);
    }

    private static Response toResponse(Query query, QueryResults queryResults)
    {
        ResponseBuilder response = Response.ok(queryResults);

        query.getSetCatalog().ifPresent(catalog -> response.header(PRESTO_SET_CATALOG, catalog));
        query.getSetSchema().ifPresent(schema -> response.header(PRESTO_SET_SCHEMA, schema));
        query.getSetPath().ifPresent(path -> response.header(PRESTO_SET_PATH, path));

        // add set session properties
        query.getSetSessionProperties().entrySet()
                .forEach(entry -> response.header(PRESTO_SET_SESSION, entry.getKey() + '=' + entry.getValue()));

        // add clear session properties
        query.getResetSessionProperties()
                .forEach(name -> response.header(PRESTO_CLEAR_SESSION, name));

        // add set roles
        query.getSetRoles().entrySet()
                .forEach(entry -> response.header(PRESTO_SET_ROLE, entry.getKey() + '=' + urlEncode(entry.getValue().toString())));

        // add added prepare statements
        for (Entry<String, String> entry : query.getAddedPreparedStatements().entrySet()) {
            String encodedKey = urlEncode(entry.getKey());
            String encodedValue = urlEncode(entry.getValue());
            response.header(PRESTO_ADDED_PREPARE, encodedKey + '=' + encodedValue);
        }

        // add deallocated prepare statements
        for (String name : query.getDeallocatedPreparedStatements()) {
            response.header(PRESTO_DEALLOCATED_PREPARE, urlEncode(name));
        }

        // add new transaction ID
        query.getStartedTransactionId()
                .ifPresent(transactionId -> response.header(PRESTO_STARTED_TRANSACTION_ID, transactionId));

        // add clear transaction ID directive
        if (query.isClearTransactionId()) {
            response.header(PRESTO_CLEAR_TRANSACTION_ID, true);
        }

        return response.build();
    }

    /**
     * //地址为: /v1/statemen/queryID/ token的DELETE请求，由以下方法处理，该方法用于取消一个
     Query。在该方法中并没有使用Token,因此Token在这里的作用只是用于区别于QueryResource中的
     cancleQuery方法，而且该方法与QueryResource中的cancelQuery方法的区别还在于加入了一些特殊情
     情况的处理和exchangeClient的关闭和清理

     * @param queryId
     * @param token
     * @return
     */
    @DELETE
    @Path("{queryId}/{token}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelQuery(@PathParam("queryId") QueryId queryId,
            @PathParam("token") long token)
    {
        Query query = queries.get(queryId);
        if (query == null) {
            return Response.status(Status.NOT_FOUND).build();
        }
        query.cancel();
        return Response.noContent().build();
    }

    private static String urlEncode(String value)
    {
        try {
            return URLEncoder.encode(value, "UTF-8");
        }
        catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    @Managed
    @Nested
    public CounterStat getCreateQueryRequests()
    {
        return createQueryRequests;
    }
}
