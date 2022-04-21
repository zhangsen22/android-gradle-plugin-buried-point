/*
 * Created by wangzhuohou on 2015/08/01.
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

import com.sensorsdata.analytics.android.plugin.hook.config.SensorsFragmentHookConfig
import com.sensorsdata.analytics.android.plugin.utils.VersionUtils
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

class SensorsAnalyticsClassVisitor extends ClassVisitor {
    private String mClassName
    private String mSuperName
    private String[] mInterfaces
    private HashSet<String> visitedFragMethods = new HashSet<>()// 无需判空
    private boolean isFoundOnNewIntent = false
    private ClassVisitor classVisitor

    private SensorsAnalyticsTransformHelper transformHelper

    private ClassNameAnalytics classNameAnalytics

    private ArrayList<SensorsAnalyticsMethodCell> methodCells = new ArrayList<>()

    private int version

    private HashMap<String, SensorsAnalyticsMethodCell> mLambdaMethodCells = new HashMap<>()

    private boolean isExtendsActivity
    // 是否是 AndroidTV 版本
    private boolean isAndroidTv

    @Override
    protected Object clone() throws CloneNotSupportedException {
        return super.clone()
    }

    SensorsAnalyticsClassVisitor(final ClassVisitor classVisitor, ClassNameAnalytics classNameAnalytics, SensorsAnalyticsTransformHelper transformHelper) {
        super(SensorsAnalyticsUtil.ASM_VERSION, classVisitor)
        this.classVisitor = classVisitor
        this.classNameAnalytics = classNameAnalytics
        this.transformHelper = transformHelper
        isAndroidTv = VersionUtils.isTvVersion()
    }

    private
    static void visitMethodWithLoadedParams(MethodVisitor methodVisitor, int opcode, String owner, String methodName, String methodDesc, int start, int count, List<Integer> paramOpcodes) {
        for (int i = start; i < start + count; i++) {
            methodVisitor.visitVarInsn(paramOpcodes[i - start], i)
        }
        methodVisitor.visitMethodInsn(opcode, owner, methodName, methodDesc, false)
    }

    /**
     * 该方法是当扫描类时第一个拜访的方法，主要用于类声明使用
     * @param version 表示类版本：51，表示 “.class” 文件的版本是 JDK 1.7
     * @param access 类的修饰符：修饰符在 ASM 中是以 “ACC_” 开头的常量进行定义。
     *                          可以作用到类级别上的修饰符有：ACC_PUBLIC（public）、ACC_PRIVATE（private）、ACC_PROTECTED（protected）、
     *                          ACC_FINAL（final）、ACC_SUPER（extends）、ACC_INTERFACE（接口）、ACC_ABSTRACT（抽象类）、
     *                          ACC_ANNOTATION（注解类型）、ACC_ENUM（枚举类型）、ACC_DEPRECATED（标记了@Deprecated注解的类）、ACC_SYNTHETIC
     * @param name 类的名称：通常我们的类完整类名使用 “org.test.mypackage.MyClass” 来表示，但是到了字节码中会以路径形式表示它们 “org/test/mypackage/MyClass” 。
     *                      值得注意的是虽然是路径表示法但是不需要写明类的 “.class” 扩展名。
     * @param signature 表示泛型信息，如果类并未定义任何泛型该参数为空
     * @param superName 表示所继承的父类：由于 Java 的类是单根结构，即所有类都继承自 java.lang.Object。 因此可以简单的理解为任何类都会具有一个父类。
     *                  虽然在编写 Java 程序时我们没有去写 extends 关键字去明确继承的父类，但是 JDK在编译时 总会为我们加上 “ extends Object”。
     * @param interfaces 表示类实现的接口，在 Java 中类是可以实现多个不同的接口因此此处是一个数组。
     */
    @Override
    void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        mClassName = name
        mSuperName = superName
        mInterfaces = interfaces
        this.version = version
        super.visit(version, access, name, signature, superName, interfaces)
        isExtendsActivity = SensorsAnalyticsUtil.isInstanceOfActivity(superName)
        if (Logger.debug) {
            Logger.info("开始扫描类：${mClassName}")
            Logger.info("类详情：version=${version};\taccess=${Logger.accCode2String(access)};\tname=${name};\tsignature=${signature};\tsuperName=${superName};\tinterfaces=${interfaces.toArrayString()}\n")
        }
    }


    /**
     * 该方法是当扫描器完成类扫描时才会调用，如果想在类中追加某些方法，可以在该方法中实现。
     */
    @Override
    void visitEnd() {
        super.visitEnd()
        if (SensorsAnalyticsUtil.isInstanceOfFragment(mSuperName)) {
            MethodVisitor mv
            // 添加剩下的方法，确保super.onHiddenChanged(hidden);等先被调用
            Iterator<Map.Entry<String, SensorsAnalyticsMethodCell>> iterator = SensorsFragmentHookConfig.FRAGMENT_METHODS.entrySet().iterator()
            while (iterator.hasNext()) {
                Map.Entry<String, SensorsAnalyticsMethodCell> entry = iterator.next()
                String key = entry.getKey()
                SensorsAnalyticsMethodCell methodCell = entry.getValue()
                if (visitedFragMethods.contains(key)) {
                    continue
                }
                mv = classVisitor.visitMethod(Opcodes.ACC_PUBLIC, methodCell.name, methodCell.desc, null, null)
                mv.visitCode()
                // call super
                visitMethodWithLoadedParams(mv, Opcodes.INVOKESPECIAL, mSuperName, methodCell.name, methodCell.desc, methodCell.paramsStart, methodCell.paramsCount, methodCell.opcodes)
                // call injected method
                visitMethodWithLoadedParams(mv, Opcodes.INVOKESTATIC, SensorsFragmentHookConfig.SENSORS_FRAGMENT_TRACK_HELPER_API, methodCell.agentName, methodCell.agentDesc, methodCell.paramsStart, methodCell.paramsCount, methodCell.opcodes)
                mv.visitInsn(Opcodes.RETURN)
                mv.visitMaxs(methodCell.paramsCount, methodCell.paramsCount)
                mv.visitEnd()
            }
        }
        if (Logger.debug) {
            Logger.info("结束扫描类：${mClassName}\n")
        }
    }


    @Override
    FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        if (classNameAnalytics.isSensorsDataAPI) {
            if ('VERSION' == name) {
                String version = (String) value
                if (SensorsAnalyticsUtil.compareVersion(SensorsAnalyticsTransform.MIN_SDK_VERSION, version) > 0) {
                    String errMessage = "你目前集成的神策埋点 SDK 版本号为 v${version}，请升级到 v${SensorsAnalyticsTransform.MIN_SDK_VERSION} 及以上的版本。详情请参考：https://github.com/sensorsdata/sa-sdk-android"
                    Logger.error(errMessage)
                    throw new Error(errMessage)
                }
            } else if ('MIN_PLUGIN_VERSION' == name) {
                String minPluginVersion = (String) value
                if (minPluginVersion != "" && minPluginVersion != null) {
                    if (SensorsAnalyticsUtil.compareVersion(SensorsAnalyticsTransform.VERSION, minPluginVersion) < 0) {
                        String errMessage = "你目前集成的神策插件版本号为 v${SensorsAnalyticsTransform.VERSION}，请升级到 v${minPluginVersion} 及以上的版本。详情请参考：https://github.com/sensorsdata/sa-sdk-android-plugin2"
                        Logger.error(errMessage)
                        throw new Error(errMessage)
                    }
                }
            }
        }
        return super.visitField(access, name, descriptor, signature, value)
    }

    /**
     * 该方法是当扫描器扫描到类的方法时进行调用
     * @param access 表示方法的修饰符
     * @param name 表示方法名，在 ASM 中 “visitMethod” 方法会处理（构造方法、静态代码块、私有方法、受保护的方法、共有方法、native类型方法）。
     *                  在这些范畴中构造方法的方法名为 “<init>”，静态代码块的方法名为 “<clinit>”。
     * @param desc 表示方法签名，方法签名的格式如下：“(参数列表)返回值类型”
     * @param signature 凡是具有泛型信息的方法，该参数都会有值。并且该值的内容信息基本等于第三个参数的拷贝，只不过不同的是泛型参数被特殊标记出来
     * @param exceptions 用来表示将会抛出的异常，如果方法不会抛出异常，则该参数为空
     * @return
     */
    @Override
    MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        for (methodCell in classNameAnalytics.methodCells) {
            if (methodCell.name == name && methodCell.desc == desc) {
                methodCells.add(methodCell)
                return null
            }
        }
        if (mSuperName == "android/app/Activity" && name == "onNewIntent" && desc == "(Landroid/content/Intent;)V") {
            isFoundOnNewIntent = true
        }

        MethodVisitor methodVisitor = cv.visitMethod(access, name, desc, signature, exceptions)

        SensorsAnalyticsDefaultMethodVisitor sensorsAnalyticsDefaultMethodVisitor = new SensorsAnalyticsDefaultMethodVisitor(methodVisitor, access, name, desc) {
            boolean isSensorsDataTrackViewOnClickAnnotation = false
            String eventName = null
            String eventProperties = null
            boolean isHasTracked = false
            int variableID = 0
            //name + desc
            String nameDesc

            //访问权限是public并且非静态
            boolean pubAndNoStaticAccess
            boolean protectedAndNotStaticAccess
            ArrayList<Integer> localIds


            @Override
            void visitEnd() {
                super.visitEnd()
                if (isHasTracked) {
                    if (transformHelper.extension.lambdaEnabled && mLambdaMethodCells.containsKey(nameDesc)) {
                        mLambdaMethodCells.remove(nameDesc)
                    }
                    Logger.info("Hooked method: ${name}${desc}\n")
                }
            }

            @Override
            void visitInvokeDynamicInsn(String name1, String desc1, Handle bsm, Object... bsmArgs) {
                super.visitInvokeDynamicInsn(name1, desc1, bsm, bsmArgs)
                if (!transformHelper.extension.lambdaEnabled) {
                    return
                }
                try {
                    String desc2 = (String) bsmArgs[0]
                    SensorsAnalyticsMethodCell sensorsAnalyticsMethodCell = SensorsAnalyticsHookConfig.LAMBDA_METHODS.get(Type.getReturnType(desc1).getDescriptor() + name1 + desc2)
                    if (sensorsAnalyticsMethodCell != null) {
                        Handle it = (Handle) bsmArgs[1]
                        mLambdaMethodCells.put(it.name + it.desc, sensorsAnalyticsMethodCell)
                    }
                } catch (Exception e) {
                    e.printStackTrace()
                }
            }

            @Override
            protected void onMethodEnter() {
                super.onMethodEnter()
                nameDesc = name + desc

                pubAndNoStaticAccess = SensorsAnalyticsUtil.isPublic(access) && !SensorsAnalyticsUtil.isStatic(access)
                protectedAndNotStaticAccess = SensorsAnalyticsUtil.isProtected(access) && !SensorsAnalyticsUtil.isStatic(access)
                if (pubAndNoStaticAccess) {
                     if (SensorsAnalyticsUtil.isInstanceOfFragment(mSuperName)
                            && SensorsFragmentHookConfig.FRAGMENT_METHODS.get(nameDesc) != null) {
                        SensorsAnalyticsMethodCell sensorsAnalyticsMethodCell = SensorsFragmentHookConfig.FRAGMENT_METHODS.get(nameDesc)
                        localIds = new ArrayList<>()
                        Type[] types = Type.getArgumentTypes(desc)
                        for (int i = 1; i < sensorsAnalyticsMethodCell.paramsCount; i++) {
                            int localId = newLocal(types[i - 1])
                            methodVisitor.visitVarInsn(sensorsAnalyticsMethodCell.opcodes.get(i), i)
                            methodVisitor.visitVarInsn(SensorsAnalyticsUtil.convertOpcodes(sensorsAnalyticsMethodCell.opcodes.get(i)), localId)
                            localIds.add(localId)
                        }
                    }
                }

                // Lambda 参数优化部分，对现有参数进行复制
                if (transformHelper.extension.lambdaEnabled) {
                    SensorsAnalyticsMethodCell lambdaMethodCell = mLambdaMethodCells.get(nameDesc)
                    if (lambdaMethodCell != null) {
                        //判断是否是在采样中，在采样中才会处理或者开关打开也统一处理
                        if (transformHelper.extension.lambdaParamOptimize || SensorsAnalyticsHookConfig.SAMPLING_LAMBDA_METHODS.contains(lambdaMethodCell)) {
                            Type[] types = Type.getArgumentTypes(lambdaMethodCell.desc)
                            int length = types.length
                            Type[] lambdaTypes = Type.getArgumentTypes(desc)
                            // paramStart 为访问的方法参数的下标，从 0 开始
                            int paramStart = lambdaTypes.length - length
                            if (paramStart < 0) {
                                return
                            } else {
                                for (int i = 0; i < length; i++) {
                                    if (lambdaTypes[paramStart + i].descriptor != types[i].descriptor) {
                                        return
                                    }
                                }
                            }
                            boolean isStaticMethod = SensorsAnalyticsUtil.isStatic(access)
                            localIds = new ArrayList<>()
                            for (int i = paramStart; i < paramStart + lambdaMethodCell.paramsCount; i++) {
                                int localId = newLocal(types[i - paramStart])
                                methodVisitor.visitVarInsn(lambdaMethodCell.opcodes.get(i - paramStart), getVisitPosition(lambdaTypes, i, isStaticMethod))
                                methodVisitor.visitVarInsn(SensorsAnalyticsUtil.convertOpcodes(lambdaMethodCell.opcodes.get(i - paramStart)), localId)
                                localIds.add(localId)
                            }
                        }
                    }
                }

                if (transformHelper.isHookOnMethodEnter) {
                    handleCode()
                }
            }

            @Override
            protected void onMethodExit(int opcode) {
                super.onMethodExit(opcode)
                if (!transformHelper.isHookOnMethodEnter) {
                    handleCode()
                }
            }

            @Override
            void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
                if (classNameAnalytics.isSensorsDataAPI && "ANDROID_PLUGIN_VERSION" == fieldName && opcode == PUTSTATIC) {
                    methodVisitor.visitLdcInsn(SensorsAnalyticsTransform.VERSION)
                }
                if (classNameAnalytics.isKeyboardViewUtil && !transformHelper.extension.disableTrackKeyboard && "isSensorsCheckKeyboard" == fieldName && opcode == PUTSTATIC) {
                    methodVisitor.visitInsn(ICONST_0);
                }
                super.visitFieldInsn(opcode, owner, fieldName, fieldDesc)
            }

            void handleCode() {
                if (classNameAnalytics.isSensorsDataAPI) {
                    return
                }

                /**
                 * Fragment
                 * 目前支持以下 Fragment 页面浏览事件：
                 * android/app/Fragment，android/app/ListFragment， android/app/DialogFragment，
                 * android/support/v4/app/Fragment，android/support/v4/app/ListFragment，android/support/v4/app/DialogFragment，
                 * androidx/appcompat/app/AppCompatDialogFragment
                 * androidx/fragment/app/Fragment，androidx/fragment/app/ListFragment，androidx/fragment/app/DialogFragment
                 * com/google/android/material/bottomsheet/BottomSheetDialogFragment
                 */
                if (SensorsAnalyticsUtil.isInstanceOfFragment(mSuperName)) {
                    SensorsAnalyticsMethodCell sensorsAnalyticsMethodCell = SensorsFragmentHookConfig.FRAGMENT_METHODS.get(nameDesc)
                    if (sensorsAnalyticsMethodCell != null) {
                        visitedFragMethods.add(nameDesc)
                        methodVisitor.visitVarInsn(ALOAD, 0)
                        for (int i = 1; i < sensorsAnalyticsMethodCell.paramsCount; i++) {
                            methodVisitor.visitVarInsn(sensorsAnalyticsMethodCell.opcodes.get(i), localIds[i - 1])
                        }
                        methodVisitor.visitMethodInsn(INVOKESTATIC, SensorsFragmentHookConfig.SENSORS_FRAGMENT_TRACK_HELPER_API, sensorsAnalyticsMethodCell.agentName, sensorsAnalyticsMethodCell.agentDesc, false)
                        isHasTracked = true
                        return
                    }
                }

                /**
                 * 在 android.gradle 的 3.2.1 版本中，针对 view 的 setOnClickListener 方法 的 lambda 表达式做特殊处理。
                 */
                if (transformHelper.extension.lambdaEnabled) {
                    SensorsAnalyticsMethodCell lambdaMethodCell = mLambdaMethodCells.get(nameDesc)
                    if (lambdaMethodCell != null) {
                        Type[] types = Type.getArgumentTypes(lambdaMethodCell.desc)
                        int length = types.length
                        Type[] lambdaTypes = Type.getArgumentTypes(desc)
                        // paramStart 为访问的方法参数的下标，从 0 开始
                        int paramStart = lambdaTypes.length - length
                        if (paramStart < 0) {
                            return
                        } else {
                            for (int i = 0; i < length; i++) {
                                if (lambdaTypes[paramStart + i].descriptor != types[i].descriptor) {
                                    return
                                }
                            }
                        }
                        //如果在采样中，就按照最新的处理流程来操作
                        if (transformHelper.extension.lambdaParamOptimize || SensorsAnalyticsHookConfig.SAMPLING_LAMBDA_METHODS.contains(lambdaMethodCell)) {
                            for (int i = paramStart; i < paramStart + lambdaMethodCell.paramsCount; i++) {
                                methodVisitor.visitVarInsn(lambdaMethodCell.opcodes.get(i - paramStart), localIds[i - paramStart])
                            }
                        } else {
                            for (int i = paramStart; i < paramStart + lambdaMethodCell.paramsCount; i++) {
                                methodVisitor.visitVarInsn(lambdaMethodCell.opcodes.get(i - paramStart), getVisitPosition(lambdaTypes, i, isStaticMethod))
                            }
                        }
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, SensorsAnalyticsHookConfig.SENSORS_ANALYTICS_API, lambdaMethodCell.agentName, lambdaMethodCell.agentDesc, false)
                        isHasTracked = true
                        return
                    }
                }

                if (!SensorsAnalyticsUtil.isTargetClassInSpecial(mClassName)) {
                    if ((mClassName.startsWith('android/') || mClassName.startsWith('androidx/')) && !(mClassName.startsWith("android/support/v17/leanback") || mClassName.startsWith("androidx/leanback"))) {
                        return
                    }
                }

                if (isSensorsDataTrackViewOnClickAnnotation && desc == '(Landroid/view/View;)V') {
                    trackViewOnClick(methodVisitor, 1)
                    isHasTracked = true
                    return
                }

                if (eventName != null && eventName.length() != 0) {
                    methodVisitor.visitLdcInsn(eventName)
                    methodVisitor.visitLdcInsn(eventProperties)
                    methodVisitor.visitMethodInsn(INVOKESTATIC, SensorsAnalyticsHookConfig.SENSORS_ANALYTICS_API, "track", "(Ljava/lang/String;Ljava/lang/String;)V", false)
                    isHasTracked = true
                    return
                }

                if (mInterfaces != null && mInterfaces.length > 0) {
                        for (interfaceName in mInterfaces) {
                            SensorsAnalyticsMethodCell sensorsAnalyticsMethodCell = SensorsAnalyticsHookConfig.INTERFACE_METHODS.get(interfaceName + nameDesc)
                            if (sensorsAnalyticsMethodCell != null) {
                                visitMethodWithLoadedParams(methodVisitor, INVOKESTATIC, SensorsAnalyticsHookConfig.SENSORS_ANALYTICS_API, sensorsAnalyticsMethodCell.agentName, sensorsAnalyticsMethodCell.agentDesc, sensorsAnalyticsMethodCell.paramsStart, sensorsAnalyticsMethodCell.paramsCount, sensorsAnalyticsMethodCell.opcodes)
                                isHasTracked = true
                                return
                            }
                        }

                }
            }

            void trackViewOnClick(MethodVisitor mv, int index) {
                mv.visitVarInsn(ALOAD, index)
                mv.visitMethodInsn(INVOKESTATIC, SensorsAnalyticsHookConfig.SENSORS_ANALYTICS_API, "trackViewOnClick", "(Landroid/view/View;)V", false)
            }

            /**
             * 该方法是当扫描器扫描到类注解声明时进行调用
             *
             * @param s 注解的类型。它使用的是（“L” + “类型路径” + “;”）形式表述
             * @param b 表示的是，该注解是否在 JVM 中可见
             * 1.RetentionPolicy.SOURCE：声明注解只保留在 Java 源程序中，在编译 Java 类时注解信息不会被写入到 Class。如果使用的是这个配置 ASM 也将无法探测到这个注解。
             * 2.RetentionPolicy.CLASS：声明注解仅保留在 Class 文件中，JVM 运行时并不会处理它，这意味着 ASM 可以在 visitAnnotation 时候探测到它，但是通过Class 反射无法获取到注解信息。
             * 3.RetentionPolicy.RUNTIME：这是最常用的一种声明，ASM 可以探测到这个注解，同时 Java 反射也可以取得注解的信息。所有用到反射获取的注解都会用到这个配置，就是这个原因。
             * @return
             */
            @Override
            AnnotationVisitor visitAnnotation(String s, boolean b) {
                if (s == 'Lcom/sensorsdata/analytics/android/sdk/SensorsDataTrackViewOnClick;') {
                    isSensorsDataTrackViewOnClickAnnotation = true
                    Logger.info("发现 ${name}${desc} 有注解 @SensorsDataTrackViewOnClick")
                } else if (s == 'Lcom/sensorsdata/analytics/android/sdk/SensorsDataTrackEvent;') {
                    return new AnnotationVisitor(SensorsAnalyticsUtil.ASM_VERSION) {
                        @Override
                        void visit(String key, Object value) {
                            super.visit(key, value)
                            if ("eventName" == key) {
                                eventName = (String) value
                            } else if ("properties" == key) {
                                eventProperties = value.toString()
                            }
                        }
                    }
                }

                return super.visitAnnotation(s, b)
            }
        }

        return sensorsAnalyticsDefaultMethodVisitor
    }

    /**
     * 获取方法参数下标为 index 的对应 ASM index
     * @param types 方法参数类型数组
     * @param index 方法中参数下标，从 0 开始
     * @param isStaticMethod 该方法是否为静态方法
     * @return 访问该方法的 index 位参数的 ASM index
     */
    int getVisitPosition(Type[] types, int index, boolean isStaticMethod) {
        if (types == null || index < 0 || index >= types.length) {
            throw new Error("getVisitPosition error")
        }
        if (index == 0) {
            return isStaticMethod ? 0 : 1
        } else {
            return getVisitPosition(types, index - 1, isStaticMethod) + types[index - 1].getSize()
        }
    }
}