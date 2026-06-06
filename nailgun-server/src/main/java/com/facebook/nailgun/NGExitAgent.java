/*
 * Copyright 2026-present Soundtrack Your Brand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.nailgun;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

public class NGExitAgent {

  private static volatile Throwable transformationError = null;

  public static void premain(String agentArgs, Instrumentation inst) {
    inst.addTransformer(new TargetedExitTransformer(), true);
    try {
      inst.retransformClasses(Class.forName("java.lang.Runtime"));
    } catch (Throwable t) {
      throw new RuntimeException("Nailgun Agent failed to trigger retransformation", t);
    }
    if (transformationError != null) {
      throw new RuntimeException(
          "Nailgun Agent failed to rewrite java.lang.Runtime bytecode", transformationError);
    }
  }

  static class TargetedExitTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer) {

      if (!"java/lang/Runtime".equals(className)) {
        return null;
      }

      try {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor cv = new ExitTrapClassVisitor(cw);
        cr.accept(cv, 0);
        return cw.toByteArray();
      } catch (Throwable t) {
        transformationError = t;
        return null;
      }
    }
  }
}

class ExitTrapClassVisitor extends ClassVisitor {

  public ExitTrapClassVisitor(ClassWriter cw) {
    super(Opcodes.ASM9, cw);
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String descriptor, String signature, String[] exceptions) {

    if ((name.equals("exit") || name.equals("halt")) && "(I)V".equals(descriptor)) {
      boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;

      MethodVisitor mv = cv.visitMethod(access, name, descriptor, signature, exceptions);

      mv.visitCode();
      mv.visitTypeInsn(Opcodes.NEW, "java/lang/InternalError");
      mv.visitInsn(Opcodes.DUP);
      mv.visitLdcInsn("NG_EXIT_TRAP:");
      mv.visitVarInsn(Opcodes.ILOAD, isStatic ? 0 : 1);
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(I)Ljava/lang/String;", false);
      mv.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL,
          "java/lang/String",
          "concat",
          "(Ljava/lang/String;)Ljava/lang/String;",
          false);
      mv.visitMethodInsn(
          Opcodes.INVOKESPECIAL,
          "java/lang/InternalError",
          "<init>",
          "(Ljava/lang/String;)V",
          false);
      mv.visitInsn(Opcodes.ATHROW);

      mv.visitMaxs(0, 0);
      mv.visitEnd();

      return null;
    }

    return super.visitMethod(access, name, descriptor, signature, exceptions);
  }
}
