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
package com.facebook.presto.metadata;

import com.facebook.presto.connector.ConnectorId;
import com.facebook.presto.spi.NodeState;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Presto调度task 时要确保候选node都处于可工作状态，NodeManager 定义了统一的获
 取节点入口，在com.facebook.presto.server.CoordinatorModule进行注册和绑定:
 */
public interface InternalNodeManager
{
    /**
     * 获取存活的node节点列表
     * */
    Set<InternalNode> getNodes(NodeState state);

    /**
     * 根据catalogName获取存活的列表
     * */
    Set<InternalNode> getActiveConnectorNodes(ConnectorId connectorId);
    /**
     * 获取当前节点的信息
     * */
    InternalNode getCurrentNode();
    /**
     * 获取 Coordinator列表
     * */
    Set<InternalNode> getCoordinators();
    /**
     * 获取所有的节点列表
     * */
    AllNodes getAllNodes();
    /**
     * 刷新所有的节点信息
     *
     * 在调度过程中使用获取节点列表的核心方法为: refreshNodes。 该方法会在获取节点的
     各种方法中进行调用，调用频率为间隔5秒(距离上一次更新时间)。Coordinator间隔一定
     时间向各个Work节点发送http请求，如果正常响应，则表明该节点正常，否则认为是异常
     节点。在refreshNodes方法中会把这些异常节点排除，不参与Task的调度，同时各个节点
     的Version 信息必须与Coordinator 一致，否则也认为是异常节点。再根据每个节点注册的
     Catalog信息，在Coordinator 上针对每个Catalog 都会维护一个节点列表，以供
     getActiveDatasourceNodes调用。

     * */
    void refreshNodes();
    /**
     *
     * */
    void addNodeChangeListener(Consumer<AllNodes> listener);
    /**
     *
     * */
    void removeNodeChangeListener(Consumer<AllNodes> listener);
}
