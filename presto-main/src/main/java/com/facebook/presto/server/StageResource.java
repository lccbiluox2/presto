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
package com.facebook.presto.server;

import com.facebook.presto.execution.QueryManager;
import com.facebook.presto.execution.StageId;

import javax.inject.Inject;
import javax.ws.rs.DELETE;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

import static java.util.Objects.requireNonNull;

/**
 * 与Stage相关的RESTful请求均由Stage服务接口处理，其实该接口只提供了- -个功能，
 就是取消或者结束-一个指定的Stage。Stage 服务接口的实现类为StageResource
 */
@Path("/v1/stage")
public class StageResource
{
    private final QueryManager queryManager;

    @Inject
    public StageResource(QueryManager queryManager)
    {
        this.queryManager = requireNonNull(queryManager, "queryManager is null");
    }

    /**
     * //地址匁: /v1/stage/stageID 的DELETE靖求由以下方法赴理，亥方法取消或者提前結束- -个Stage
     * @param stageId
     */
    @DELETE
    @Path("{stageId}")
    public void cancelStage(@PathParam("stageId") StageId stageId)
    {
        requireNonNull(stageId, "stageId is null");
        queryManager.cancelStage(stageId);
    }
}
