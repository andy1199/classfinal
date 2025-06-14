package net.roseboy.classfinal.util;

import javassist.*;
import javassist.bytecode.*;
import javassist.compiler.CompileError;
import javassist.compiler.Javac;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 字节码操作工具类
 *
 * @author roseboy
 */
public class ClassUtils {

    /**
     * 清空方法
     *
     * @param pool      javassist的ClassPool
     * @param classname 要修改的class全名
     * @return 返回方法体的字节
     */
    public static byte[] rewriteAllMethods(ClassPool pool, String classname) {
        String name = null;
        try {
            CtClass cc = pool.getCtClass(classname);
            CtMethod[] methods = cc.getDeclaredMethods();

            for (CtMethod m : methods) {
                name = m.getName();

                // Filter for methods to process: non-constructor/initializer, belongs to the class
                if (m.getName().contains("<") || !m.getLongName().startsWith(cc.getName())) {
                    continue;
                }

                // Check for main method: public static void main(String[] args)
                boolean isPublicStatic = Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers());
                boolean isMainSignature = "main".equals(m.getName()) && "([Ljava/lang/String;)V".equals(m.getSignature());

                if (isMainSignature && isPublicStatic) {
                    try {
                        m.setBody("{ System.out.println(\"ClassFinal: Startup failed, invalid password.\"); return; }");
                    } catch (javassist.CannotCompileException e) {
                        throw new RuntimeException("Failed to set body for main method: " + m.getLongName(), e);
                    }
                } else {
                    // For other methods (non-main, non-constructor, part of this class)
                    MethodInfo methodInfo = m.getMethodInfo(); // Use getMethodInfo for consistency
                    CodeAttribute ca = methodInfo.getCodeAttribute();

                    if (!Modifier.isAbstract(m.getModifiers()) && !Modifier.isNative(m.getModifiers()) &&
                        ca != null && ca.getCodeLength() > 0) {

                        boolean isSingleReturnOrSimilar = (ca.getCodeLength() == 1 &&
                                (ca.getCode()[0] == Opcode.RETURN ||
                                 ca.getCode()[0] == Opcode.ARETURN ||
                                 ca.getCode()[0] == Opcode.DRETURN ||
                                 ca.getCode()[0] == Opcode.FRETURN ||
                                 ca.getCode()[0] == Opcode.LRETURN ||
                                 ca.getCode()[0] == Opcode.IRETURN));
                        boolean isSingleAthrow = (ca.getCodeLength() == 1 && ca.getCode()[0] == Opcode.ATHROW);

                        // Clear body if it's not already a simple return or throw.
                        // This preserves the original logic of not clearing methods that are just "athrow" (like some empty static initializers might become)
                        // or methods that are just "return".
                        if (!isSingleReturnOrSimilar && !isSingleAthrow) {
                             ClassUtils.setBodyKeepParamInfos(m, null, true);
                        }
                    }
                }

                // Common attribute removal for all processed methods (main or others)
                try {
                    MethodInfo methodInfo = m.getMethodInfo();
                    if (methodInfo != null) { // Should always be non-null for a CtMethod
                        methodInfo.removeAttribute(javassist.bytecode.LocalVariableAttribute.tag);
                        methodInfo.removeAttribute(javassist.bytecode.LineNumberAttribute.tag);
                        // System.out.println("Attempted to remove LocalVariableTable and LineNumberTable for method: " + m.getLongName());
                    }
                } catch (Exception e) {
                    // System.err.println("Error removing attributes for method: " + m.getLongName() + " - " + e.getMessage());
                }
            }
            return cc.toBytecode();
        } catch (Exception e) {
            throw new RuntimeException("[" + classname + "(" + name + ")]" + e.getMessage());
        }
    }

    /**
     * 修改方法体，并且保留参数信息
     *
     * @param m       javassist的方法
     * @param src     java代码
     * @param rebuild 是否重新构建
     * @throws CannotCompileException 编译异常
     */
    public static void setBodyKeepParamInfos(CtMethod m, String src, boolean rebuild) throws CannotCompileException {
        CtClass cc = m.getDeclaringClass();
        if (cc.isFrozen()) {
            throw new RuntimeException(cc.getName() + " class is frozen");
        }
        CodeAttribute ca = m.getMethodInfo().getCodeAttribute();
        if (ca == null) {
            throw new CannotCompileException("no method body");
        } else {
            CodeIterator iterator = ca.iterator();
            Javac jv = new Javac(cc);

            try {
                int nvars = jv.recordParams(m.getParameterTypes(), Modifier.isStatic(m.getModifiers()));
                jv.recordParamNames(ca, nvars);
                jv.recordLocalVariables(ca, 0);
                jv.recordReturnType(Descriptor.getReturnType(m.getMethodInfo().getDescriptor(), cc.getClassPool()), false);
                //jv.compileStmnt(src);
                //Bytecode b = jv.getBytecode();
                Bytecode b = jv.compileBody(m, src);
                int stack = b.getMaxStack();
                int locals = b.getMaxLocals();
                if (stack > ca.getMaxStack()) {
                    ca.setMaxStack(stack);
                }

                if (locals > ca.getMaxLocals()) {
                    ca.setMaxLocals(locals);
                }
                int pos = iterator.insertEx(b.get());
                iterator.insert(b.getExceptionTable(), pos);
                if (rebuild) {
                    m.getMethodInfo().rebuildStackMapIf6(cc.getClassPool(), cc.getClassFile2());
                }
            } catch (NotFoundException var12) {
                throw new CannotCompileException(var12);
            } catch (CompileError var13) {
                throw new CannotCompileException(var13);
            } catch (BadBytecode var14) {
                throw new CannotCompileException(var14);
            }
        }
    }

    /**
     * 加载jar包路径
     *
     * @param pool  javassist的ClassPool
     * @param paths lib路径，
     */
    public static void loadClassPath(ClassPool pool, List<String> paths) {
        for (String path : paths) {
            loadClassPath(pool, new File(path));
        }
    }

    /**
     * 加载jar包路径
     *
     * @param pool javassist的ClassPool
     * @param dir  lib路径或jar文件
     */
    public static void loadClassPath(ClassPool pool, File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }

        if (dir.isDirectory()) {
            List<File> jars = new ArrayList<>();
            IoUtils.listFile(jars, dir, ".jar");
            for (File jar : jars) {
                try {
                    pool.insertClassPath(jar.getAbsolutePath());
                } catch (NotFoundException e) {
                    //ignore
                }
            }
        } else if (dir.getName().endsWith(".jar")) {
            try {
                pool.insertClassPath(dir.getAbsolutePath());
            } catch (NotFoundException e) {
                //ignore
            }
        }
    }

    /**
     * 给方法插入代码并返回bytecode的字节数组
     *
     * @param classMethod 类名#方法名
     * @param javaCode    代码
     * @param line        行数
     * @param libDir      classpath
     * @param thisJar     本项目的jar路径
     * @return 修改后的字节数组
     * @throws Exception Exception
     */
    public static byte[] insertCode(String classMethod, String javaCode, int line, File libDir, File thisJar) throws Exception {
        String className = classMethod.split("#")[0];
        String methodName = classMethod.split("#")[1];
        ClassPool pool = ClassPool.getDefault();
        loadClassPath(pool, libDir);
        if (thisJar != null && thisJar.exists()) {
            loadClassPath(pool, thisJar);
        }
        byte[] bytes;
        CtClass cc = pool.getCtClass(className);
        if (methodName.startsWith("<") && methodName.contains(">")) {
            methodName = methodName.replace("<", "").replace(">", "");
            CtConstructor[] ms = cc.getConstructors();
            for (CtConstructor mt : ms) {
                if (mt.getLongName().endsWith(methodName)) {
                    // mt.insertAt(line, javaCode);
                    mt.insertBefore(javaCode);
                }
            }
        } else {
            CtMethod mt = cc.getDeclaredMethod(methodName);
            // mt.insertAt(line, javaCode);
            mt.insertBefore(javaCode);
        }
        bytes = cc.toBytecode();
        return bytes;
    }

}
