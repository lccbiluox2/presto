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
package com.facebook.presto.matching.example.rel;

/**
 * ProjectNode是用于进行列映射的节点，用于将ProjectNode 下 层节点输出的列映射到
 ProjectNode.上层节点输入的列，例如:

 select 1_orderkey+1 from lineitem

 ProjectNode会将其下层TableScanNode输出的I_ orderkey 作为输入，映射为1 orderkey+1
 并传递给上层节点。

 */
public class ProjectNode
        implements SingleSourceRelNode
{
    private RelNode source;

    public ProjectNode(RelNode source)
    {
        this.source = source;
    }

    public RelNode getSource()
    {
        return source;
    }
}
