/*
 * Created by wangzhuozhou on 2015/08/12.
 * Copyright 2015－2022 Sensors Data Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sensorsdata.analytics.android.plugin

import org.objectweb.asm.Opcodes

class SensorsAnalyticsHookConfig {
    public static final String SENSORS_ANALYTICS_API = "com/sensorsdata/analytics/android/sdk/SensorsDataAutoTrackHelper"
    public final static HashMap<String, SensorsAnalyticsMethodCell> INTERFACE_METHODS = new HashMap<>()

    /**
     * android.gradle 3.2.1 版本中，针对 Lambda 表达式处理
     */

    public final static HashMap<String, SensorsAnalyticsMethodCell> LAMBDA_METHODS = new HashMap<>()
    //lambda 参数优化取样
    public final static ArrayList<SensorsAnalyticsMethodCell> SAMPLING_LAMBDA_METHODS = new ArrayList<>()
    static {
        addLambdaMethod(new SensorsAnalyticsMethodCell(
                'onClick',
                '(Landroid/view/View;)V',
                'Landroid/view/View$OnClickListener;',
                'trackViewOnClick',
                '(Landroid/view/View;)V',
                1, 1,
                [Opcodes.ALOAD]))
        SAMPLING_LAMBDA_METHODS.add(new SensorsAnalyticsMethodCell(
                'onClick',
                '(Landroid/view/View;)V',
                'Landroid/view/View$OnClickListener;',
                'trackViewOnClick',
                '(Landroid/view/View;)V',
                1, 1,
                [Opcodes.ALOAD]))

        // Todo: 扩展
    }

    static void addLambdaMethod(SensorsAnalyticsMethodCell sensorsAnalyticsMethodCell) {
        if (sensorsAnalyticsMethodCell != null) {
            LAMBDA_METHODS.put(sensorsAnalyticsMethodCell.parent + sensorsAnalyticsMethodCell.name + sensorsAnalyticsMethodCell.desc, sensorsAnalyticsMethodCell)
        }
    }
}