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
package com.facebook.presto.cli;

import static io.airlift.airline.SingleCommand.singleCommand;

public final class Presto
{
    private Presto() {}

    /**
     * 命令行查询页面
     *
     * @param args
     */
    public static void main(String[] args)
    {
        /**
         * //根据传递的参数初始化一个Console对象，该对象中保存了启动Cli时传入的所有参数
         */
        Console console = singleCommand(Console.class).parse(args);

        /**
         * //若启动的时候使用了--help或者--version则会显示帮助信息或者版本信息，然后直接退出
         */
        if (console.helpOption.showHelpIfRequested() ||
                console.versionOption.showVersionIfRequested()) {
            return;
        }

        /**
         * //进入主程序，然后根据启动CLI传入的不同参数进行不同的处理
         */
        System.exit(console.run() ? 0 : 1);
    }
}
