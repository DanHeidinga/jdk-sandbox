/*
 * Copyright (c) 2016, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
/* Based on GenerateJLIClassesPlugin */
package jdk.tools.jlink.internal.plugins;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DirectMethodHandleDesc.Kind;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.LambdaConversionException;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import jdk.classfile.ClassModel;
import jdk.classfile.*;
import jdk.classfile.instruction.*;
import jdk.internal.access.JavaLangInvokeAccess;
import jdk.internal.access.SharedSecrets;
import jdk.tools.jlink.plugin.PluginException;
import jdk.tools.jlink.plugin.ResourcePool;
import jdk.tools.jlink.plugin.ResourcePoolBuilder;
import jdk.tools.jlink.plugin.ResourcePoolEntry;


public final class GenerateLambdaClassesPlugin extends AbstractPlugin {

    private static final JavaLangInvokeAccess JLIA
            = SharedSecrets.getJavaLangInvokeAccess();

    private String mainArgument;
    private Stream<String> traceFileStream;

    private ClassDesc LAMBDA_METAFACTORY_CLASSDESC = ClassDesc.of("java.lang.invoke.LambdaMetafactory");

    public GenerateLambdaClassesPlugin() {
        super("generate-lambda-classes");
    }

    @Override
    public Set<State> getState() {
        return EnumSet.of(State.AUTO_ENABLED, State.FUNCTIONAL);
    }

    @Override
    public boolean hasArguments() {
        return false;
    }

    @Override
    public void configure(Map<String, String> config) {
        mainArgument = config.get(getName());
    }

    public void initialize(ResourcePool in) {
        // Load configuration from the contents in the supplied input file
        // - if none was supplied we look for the default file
        if (mainArgument == null || !mainArgument.startsWith("@")) {
            // try (InputStream traceFile =
            //         this.getClass().getResourceAsStream(DEFAULT_TRACE_FILE)) {
            //     if (traceFile != null) {
            //         traceFileStream = new BufferedReader(new InputStreamReader(traceFile)).lines();
            //     }
            // } catch (Exception e) {
            //     throw new PluginException("Couldn't read " + DEFAULT_TRACE_FILE, e);
            // }
        } else {
            File file = new File(mainArgument.substring(1));
            if (file.exists()) {
                traceFileStream = fileLines(file);
            }
        }
    }

    private Stream<String> fileLines(File file) {
        try {
            return Files.lines(file.toPath());
        } catch (IOException io) {
            throw new PluginException("Couldn't read file");
        }
    }

    @Override
    public ResourcePool transform(ResourcePool in, ResourcePoolBuilder out) {
        initialize(in);

        // Identify any lambda metafactory calls in each ResourcePoolEntry
        // and pre-generate the appropriate classes for each lambda
        // Fix up the original class to:
        //     - change the invokedynamic to appropriate lambda class call
        //     - NestMates?  Update the NestHost and NestMember
        in.transformAndCopy(entry -> {
                ResourcePoolEntry res = entry;
                if (entry.type().equals(ResourcePoolEntry.Type.CLASS_OR_RESOURCE)) {
                    String path = entry.path();
                    if (path.endsWith(".class")) {
                        if (path.endsWith("module-info.class")) {
                            // No lambdas in a module-info class
                        } else {
                            ClassModel cm = Classfile.parse(entry.contentBytes());
                            for (MethodModel methodModel : cm.methods()) {
                                for (MethodElement methodElement : methodModel) {
                                    if (methodElement instanceof CodeModel xm) {
                                        for (CodeElement e : xm) {
                                            switch(e) {
                                                case InvokeDynamicInstruction i -> {
                                                    if (isMetafactory(i)) {
                                                        print(xm, i);
                                                        generateLambdaInnerClass(entry, out, cm, methodElement, i);
                                                        // transform the `indy` to 'new generatedClass(....)'
                                                    } else if (isAltMetafactory(i)) {
                                                        //TODO
                                                    }
                                                }
                                                default -> { }
                                            }
                                        }
                                    }
                                }
                            }

                            byte[] content = entry.contentBytes(); // In the future, this will come from the ClassModel
                            res = entry.copyWithContent(content);
                        }
                    }
                }
                return entry;
            }, out);

        /* Temp disable
        CodeTransform indyToThunk = (builder, element) -> {
            if (element instanceof InvokeDynamicInstruction i
            && isMetafactory(i)
            ) {
                CodeModel genModel = generateLambdaInnerClass(entry, out, builder.original().orElseThrow(), methodElement, i);
                builder.invokevirtual(genModel.thisClass().asSymbol(), "thunk", i.typeSymbol());

            } else {
                builder.with(element);
            }
        };
        */

        return out.build();
    }

    static void print(CodeModel codeModel, InvokeDynamicInstruction indy) {
        MethodModel meth = codeModel.parent().orElse(null);
        ClassModel cm = meth.parent().orElse(null);

        System.out.println("{  Class: " + cm.thisClass().asInternalName());
        System.out.println("     Method: " + meth.methodName().stringValue() + meth.methodType().stringValue() );
        System.out.println("       Indy " + indy);
        System.out.println("          Owner:" + indy.bootstrapMethod().owner() + "  equals:" + indy.bootstrapMethod().owner().equals(ClassDesc.of("java.lang.invoke.LambdaMetafactory")));
        System.out.println("}");
    }

    boolean isMetafactory(InvokeDynamicInstruction indy) {
        //                                                          interfaceMethodName, factoryType, interfaceMethodType, impl, dynamicMethodType
        //STATIC/LambdaMetafactory::metafactory(MethodHandles$Lookup,String,MethodType,MethodType,MethodHandle,MethodType)CallSite]
        DirectMethodHandleDesc bsm = indy.bootstrapMethod();
        if (bsm.methodName().equals("metafactory")
        && LAMBDA_METAFACTORY_CLASSDESC.equals(bsm.owner())
        && bsm.kind().equals(DirectMethodHandleDesc.Kind.STATIC)
        ) {
            return true;
        }
        return false;
    }

    boolean isAltMetafactory(InvokeDynamicInstruction indy) {
        //STATIC/LambdaMetafactory::altMetafactory(MethodHandles$Lookup,String,MethodType,Object...)CallSite]
        DirectMethodHandleDesc bsm = indy.bootstrapMethod();
        if (bsm.methodName().equals("altMetafactory")
        && LAMBDA_METAFACTORY_CLASSDESC.equals(bsm.owner())
        && bsm.kind().equals(DirectMethodHandleDesc.Kind.STATIC)
        ) {
            return true;
        }
        return false;
    }


    /**
     * Generate the InnerClass for the lambda meta factory
     *
     * @param entry ResourcePoolEntry representing the original class
     * @param out ResourcePoolBuilder to ensure class is added to the jlinked image
     * @param cm ClassModel for class defining the lambda (invokedynamic instruction)
     * @param methodElement MethodElement for context around the indy instruction
     * @param indy the instruction itself to pull the symbolic data from
     * @return a ClassModel representing the generated class
     */
    ClassModel generateLambdaInnerClass(ResourcePoolEntry entry, ResourcePoolBuilder out, ClassModel cm, MethodElement methodElement, InvokeDynamicInstruction indy) {
        //                                                    lookup, String interfaceMethodName, factoryType, interfaceMethodType, impl, dynamicMethodType
        //STATIC/LambdaMetafactory::metafactory(MethodHandles$Lookup,
        // String --> interfaceMethodName,
        // MethodType --> factoryType,
        // MethodType --> interfaceMethodType,
        // MethodHandle --> implementation,
        // MethodType --> dynamicMethodType )CallSite]
        /*
         {  Class: jdk/nio/zipfs/ZipFileSystem
            Method: close()V
            Indy InvokeDynamic[OP=INVOKEDYNAMIC,
                bsm=MethodHandleDesc[STATIC/LambdaMetafactory::metafactory(MethodHandles$Lookup,String,MethodType,MethodType,MethodHandle,MethodType)CallSite]
                    0 [MethodTypeDesc[()Object],
                    1 MethodHandleDesc[STATIC/ZipFileSystem::lambda$close$10(Path)Boolean],
                    2 MethodTypeDesc[()Boolean]]]
                bsm Owner:ClassDesc[LambdaMetafactory]  equals:true
         }
        */
        List<ConstantDesc> bsmArgs = indy.bootstrapArgs();
        MethodTypeDesc indyTypeSymbol = indy.typeSymbol();


        String interfaceMethodName = indy.name().stringValue();
        MethodTypeDesc interfaceMethodType = (MethodTypeDesc) bsmArgs.get(0);
        MethodTypeDesc dynamicMethodType = (MethodTypeDesc) bsmArgs.get(2);
        ClassDesc targetClassDesc = cm.thisClass().asSymbol(); //Class calling the metafactory
        MethodTypeDesc factoryTypeDesc = indy.typeSymbol();
        ClassDesc interfaceDesc = factoryTypeDesc.returnType();
        String[] interfaceNames = new String[] { interfaceDesc.descriptorString() }; // from the altMetafactory Classes[] altInterfaces array.  Must include interface name
        boolean isSerializable = false; // only set by altMetafactory
        boolean accidentallySerializable = false; // not sure we can tell at jlink time without a full dictionary of the classes (heirarchy)
        DirectMethodHandleDesc implementation = (DirectMethodHandleDesc) bsmArgs.get(1);
        int implKind = implementation.refKind();
        MethodTypeDesc[] altMethodDescs = null; // OK for metafactory, fill in for altMetafactory


        try {
            byte[] bytes = JLIA.generateLambdaInnerClasses(
                interfaceMethodName,
                interfaceMethodType,
                dynamicMethodType,
                targetClassDesc,
                interfaceNames,
                factoryTypeDesc,
                isSerializable,
                accidentallySerializable,
                implementation, implKind,
                altMethodDescs);
            ClassModel genModel = Classfile.parse(bytes);
            String entryName = "/" + entry.moduleName() + "/" + genModel.thisClass().asInternalName() + ".class";
            ResourcePoolEntry ndata = ResourcePoolEntry.create(entryName, bytes);
            System.out.println("Generated: " + entryName);
            out.add(ndata);
            return genModel;
        } catch(LambdaConversionException e) {
            //TODO: log and continue
        }
        return null; // TODO
        /*
        public InnerClassLambdaGenerator(
            String interfaceMethodName, DONE
            MethodTypeDesc interfaceMethodTypeDesc, DONE
            MethodTypeDesc dynamicMethodTypeDesc, DONE
            ClassDesc targetClassDesc,
            String[] interfaceNames,
            MethodTypeDesc factoryTypeDesc,
            boolean isSerializable,
            boolean accidentallySerializable,
            DirectMethodHandleDesc implMHDesc,
            int implKind,
            MethodTypeDesc[] altMethodDescs);
        */
    }

}
