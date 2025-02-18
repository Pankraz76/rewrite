/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.style.ImportLayoutStyle;
import org.openrewrite.java.style.IntelliJ;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;
import org.openrewrite.style.Style;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptySet;
import static org.openrewrite.java.style.ImportLayoutStyle.isPackageAlwaysFolded;
import static org.openrewrite.java.tree.TypeUtils.fullyQualifiedNamesAreEqual;
import static org.openrewrite.java.tree.TypeUtils.toFullyQualifiedName;

/**
 * This recipe will remove any imports for types that are not referenced within the compilation unit. This recipe
 * is aware of the import layout style and will correctly handle unfolding of wildcard imports if the import counts
 * drop below the configured values.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveUnusedImports extends Recipe {

    @Override
    public String getDisplayName() {
        return "Remove unused imports";
    }

    @Override
    public String getDescription() {
        return "Remove imports for types that are not referenced. As a precaution against incorrect changes no imports " +
               "will be removed from any source where unknown types are referenced. The most common cause of unknown " +
               "types is the use of annotation processors not supported by OpenRewrite, such as lombok.";
    }

    @Override
    public Set<String> getTags() {
        return Collections.singleton("RSPEC-S1128");
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(5);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new NoMissingTypes(), new RemoveUnusedImportsVisitor());
    }

    private static class RemoveUnusedImportsVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            Map<String, TreeSet<String>> methodsAndFieldsByTypeName = methodsAndFieldsByTypeName(cu);
            Map<String, Set<JavaType.FullyQualified>> typesByPackage = typesByPackage(cu);
            boolean changed = false;
            // the key is a list because a star import may get replaced with multiple unfolded imports
            List<ImportUsage> importUsage = new ArrayList<>(cu.getPadding().getImports().size());
            for (JRightPadded<J.Import> anImport : cu.getPadding().getImports()) {
                // assume initially that all imports are unused
                ImportUsage singleUsage = new ImportUsage();
                singleUsage.imports.add(anImport);
                importUsage.add(singleUsage);
            }
            // whenever an import statement is found to be used and not already in use it should be marked true
            Set<String> checkedImports = new HashSet<>();
            Set<String> usedWildcardImports = new HashSet<>();
            Set<String> usedStaticWildcardImports = new HashSet<>();
            ImportLayoutStyle layoutStyle =
                    Optional.ofNullable(Style.from(ImportLayoutStyle.class, cu)).orElse(IntelliJ.importLayout());
            for (ImportUsage anImport : importUsage) {
                J.Import elem = anImport.imports.get(0).getElement();
                J.FieldAccess qualid = elem.getQualid();
                J.Identifier name = qualid.getName();

                if (checkedImports.contains(elem.toString())) {
                    anImport.used = false;
                    changed = true;
                } else if (elem.isStatic()) {
                    String outerType = elem.getTypeName();
                    SortedSet<String> methodsAndFields = methodsAndFieldsByTypeName.get(outerType);

                    // some class names are not handled properly by `getTypeName()`
                    // see https://github.com/openrewrite/rewrite/issues/1698 for more detail
                    String target = qualid.getTarget().toString();
                    String modifiedTarget = methodsAndFieldsByTypeName.keySet().stream()
                            .filter((fqn) -> fullyQualifiedNamesAreEqual(target, fqn))
                            .findFirst()
                            .orElse(target);
                    SortedSet<String> targetMethodsAndFields = methodsAndFieldsByTypeName.get(modifiedTarget);

                    Set<JavaType.FullyQualified> staticClasses = null;
                    for (JavaType.FullyQualified maybeStatic : typesByPackage.getOrDefault(target, emptySet())) {
                        if (maybeStatic.getOwningClass() != null && outerType.startsWith(maybeStatic.getOwningClass().getFullyQualifiedName())) {
                            if (staticClasses == null) {
                                staticClasses = new HashSet<>();
                            }
                            staticClasses.add(maybeStatic);
                        }
                    }

                    if (methodsAndFields == null && targetMethodsAndFields == null && staticClasses == null) {
                        anImport.used = false;
                        changed = true;
                    } else if ("*".equals(qualid.getSimpleName())) {
                        if (isPackageAlwaysFolded(layoutStyle.getPackagesToFold(), elem)) {
                            anImport.used = true;
                            usedStaticWildcardImports.add(elem.getTypeName());
                        } else if (((methodsAndFields == null ? 0 : methodsAndFields.size()) +
                                (staticClasses == null ? 0 : staticClasses.size())) < layoutStyle.getNameCountToUseStarImport()) {
                            // replacing the star with a series of unfolded imports
                            anImport.imports.clear();

                            // add each unfolded import
                            if (methodsAndFields != null) {
                                for (String method : methodsAndFields) {
                                    anImport.imports.add(new JRightPadded<>(elem
                                            .withQualid(qualid.withName(name.withSimpleName(method)))
                                            .withPrefix(Space.format("\n")), Space.EMPTY, Markers.EMPTY));
                                }
                            }

                            if (staticClasses != null) {
                                for (JavaType.FullyQualified fqn : staticClasses) {
                                    anImport.imports.add(new JRightPadded<>(elem
                                            .withQualid(qualid.withName(name.withSimpleName(fqn.getClassName().contains(".") ? fqn.getClassName().substring(fqn.getClassName().lastIndexOf(".") + 1) : fqn.getClassName())))
                                            .withPrefix(Space.format("\n")), Space.EMPTY, Markers.EMPTY));
                                }
                            }

                            // move whatever the original prefix of the star import was to the first unfolded import
                            anImport.imports.set(0, anImport.imports.get(0).withElement(anImport.imports.get(0)
                                    .getElement().withPrefix(elem.getPrefix())));

                            changed = true;
                        } else {
                            usedStaticWildcardImports.add(elem.getTypeName());
                        }
                    } else if (staticClasses != null && staticClasses.stream().anyMatch(c -> elem.getTypeName().equals(c.getFullyQualifiedName())) ||
                            (methodsAndFields != null && methodsAndFields.contains(qualid.getSimpleName())) ||
                            (targetMethodsAndFields != null && targetMethodsAndFields.contains(qualid.getSimpleName()))) {
                        anImport.used = true;
                    } else {
                        anImport.used = false;
                        changed = true;
                    }
                } else {
                    String target = qualid.getTarget().toString();
                    Set<JavaType.FullyQualified> types = typesByPackage.getOrDefault(target, new HashSet<>());
                    Set<JavaType.FullyQualified> typesByFullyQualifiedClassPath =
                            typesByPackage.getOrDefault(toFullyQualifiedName(target), new HashSet<>());
                    Set<JavaType.FullyQualified> combinedTypes = Stream.concat(types.stream(),
                                    typesByFullyQualifiedClassPath.stream())
                            .collect(Collectors.toSet());
                    JavaType.FullyQualified qualidType = TypeUtils.asFullyQualified(elem.getQualid().getType());
                    String sourcePackage = cu.getPackageDeclaration() == null
                            ? ""
                            : cu.getPackageDeclaration().getExpression().printTrimmed(getCursor())
                            .replaceAll("\\s", "");
                    if (combinedTypes.isEmpty() || sourcePackage.equals(elem.getPackageName()) && qualidType != null && !qualidType.getFullyQualifiedName().contains("$")) {
                        anImport.used = false;
                        changed = true;
                    } else if ("*".equals(elem.getQualid().getSimpleName())) {
                        if (isPackageAlwaysFolded(layoutStyle.getPackagesToFold(), elem)) {
                            anImport.used = true;
                            usedWildcardImports.add(elem.getPackageName());
                        } else if (combinedTypes.size() < layoutStyle.getClassCountToUseStarImport()) {
                            // replacing the star with a series of unfolded imports
                            anImport.imports.clear();

                            // add each unfolded import
                            combinedTypes.stream().map(JavaType.FullyQualified::getClassName).sorted().distinct().forEach(type ->
                                    anImport.imports.add(new JRightPadded<>(elem
                                            .withQualid(qualid.withName(name.withSimpleName(type.substring(type.lastIndexOf('.') + 1))))
                                            .withPrefix(Space.format("\n")), Space.EMPTY, Markers.EMPTY))
                            );

                            // move whatever the original prefix of the star import was to the first unfolded import
                            anImport.imports.set(0, anImport.imports.get(0).withElement(anImport.imports.get(0)
                                    .getElement().withPrefix(elem.getPrefix())));

                            changed = true;
                        } else {
                            usedWildcardImports.add(target);
                        }
                    } else if (combinedTypes.stream().noneMatch(c -> {
                        if ("*".equals(elem.getQualid().getSimpleName())) {
                            return elem.getPackageName().equals(c.getPackageName());
                        }
                        return fullyQualifiedNamesAreEqual(c.getFullyQualifiedName(), elem.getTypeName());
                    })) {
                        anImport.used = false;
                        changed = true;
                    }
                }
                checkedImports.add(elem.toString());
            }

            // Do not use direct imports that are imported by a wildcard import
            Set<String> ambiguousStaticImportNames = getAmbiguousStaticImportNames(cu);
            for (ImportUsage anImport : importUsage) {
                J.Import elem = anImport.imports.get(0).getElement();
                if (!"*".equals(elem.getQualid().getSimpleName())) {
                    if (elem.isStatic()) {
                        if (usedStaticWildcardImports.contains(elem.getTypeName()) &&
                                !ambiguousStaticImportNames.contains(elem.getQualid().getSimpleName())) {
                            anImport.used = false;
                            changed = true;
                        }
                    } else {
                        if (usedWildcardImports.size() == 1 && usedWildcardImports.contains(elem.getPackageName()) && !elem.getTypeName().contains("$") && !conflictsWithJavaLang(elem)) {
                            anImport.used = false;
                            changed = true;
                        }
                    }
                }
            }

            if (changed) {
                List<JRightPadded<J.Import>> imports = new ArrayList<>();
                Space lastUnusedImportSpace = null;
                for (ImportUsage anImportGroup : importUsage) {
                    if (anImportGroup.used) {
                        List<JRightPadded<J.Import>> importGroup = anImportGroup.imports;
                        for (int i = 0; i < importGroup.size(); i++) {
                            JRightPadded<J.Import> anImport = importGroup.get(i);
                            if (i == 0 && lastUnusedImportSpace != null && anImport.getElement().getPrefix().getLastWhitespace()
                                    .chars().filter(c -> c == '\n').count() <= 1) {
                                anImport =
                                        anImport.withElement(anImport.getElement().withPrefix(lastUnusedImportSpace));
                            }
                            imports.add(anImport);
                        }
                        lastUnusedImportSpace = null;
                    } else if (lastUnusedImportSpace == null) {
                        lastUnusedImportSpace = anImportGroup.imports.get(0).getElement().getPrefix();
                    }
                }

                cu = cu.getPadding().withImports(imports);
                if (cu.getImports().isEmpty() && !cu.getClasses().isEmpty()) {
                    cu = autoFormat(cu, cu.getClasses().get(0).getName(), ctx, getCursor().getParentOrThrow());
                }
            }

            return cu;
        }
    }

    private static @NotNull Map<String, Set<JavaType.FullyQualified>> typesByPackage(final J.CompilationUnit cu) {
        Map<String, Set<JavaType.FullyQualified>> typesByPackage = new HashMap<>();
        for (JavaType javaType : cu.getTypesInUse().getTypesInUse()) {
            if (javaType instanceof JavaType.Parameterized) {
                JavaType.Parameterized parameterized = (JavaType.Parameterized) javaType;
                typesByPackage.computeIfAbsent(parameterized.getType().getPackageName(), f -> new HashSet<>())
                        .add(parameterized.getType());
                for (JavaType typeParameter : parameterized.getTypeParameters()) {
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(typeParameter);
                    if (fq != null) {
                        typesByPackage.computeIfAbsent(
                                fq.getOwningClass() == null ?
                                        fq.getPackageName() :
                                        toFullyQualifiedName(fq.getOwningClass().getFullyQualifiedName()),
                                f -> new HashSet<>()).add(fq);
                    }
                }
            } else if (javaType instanceof JavaType.FullyQualified) {
                JavaType.FullyQualified fq = (JavaType.FullyQualified) javaType;
                typesByPackage.computeIfAbsent(
                        fq.getOwningClass() == null ?
                                fq.getPackageName() :
                                toFullyQualifiedName(fq.getOwningClass().getFullyQualifiedName()),
                        f -> new HashSet<>()).add(fq);
            }
        }
        return typesByPackage;
    }

    private static @NotNull Map<String, TreeSet<String>> methodsAndFieldsByTypeName(final J.CompilationUnit cu) {
        Map<String, TreeSet<String>> methodsAndFieldsByTypeName = new HashMap<>();
        for (JavaType.Method method : cu.getTypesInUse().getUsedMethods()) {
            if (method.hasFlags(Flag.Static)) {
                methodsAndFieldsByTypeName.computeIfAbsent(method.getDeclaringType().getFullyQualifiedName(),
                                t -> new TreeSet<>())
                        .add(method.getName());
            }
        }

        for (JavaType.Variable variable : cu.getTypesInUse().getVariables()) {
            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(variable.getOwner());
            if (fq != null) {
                methodsAndFieldsByTypeName.computeIfAbsent(fq.getFullyQualifiedName(), f -> new TreeSet<>())
                        .add(variable.getName());
            }
        }
        return methodsAndFieldsByTypeName;
    }

    private static Set<String> getAmbiguousStaticImportNames(J.CompilationUnit cu) {
        Set<String> typesWithWildcardImport = new HashSet<>();
        for (J.Import elem : cu.getImports()) {
            if ("*".equals(elem.getQualid().getSimpleName())) {
                typesWithWildcardImport.add(elem.getTypeName());
            }
        }
        Set<JavaType.FullyQualified> qualifiedTypes = new HashSet<>();
        for (JavaType.Variable variable : cu.getTypesInUse().getVariables()) {
            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(variable.getOwner());
            if (fq != null && typesWithWildcardImport.contains(fq.getFullyQualifiedName())) {
                qualifiedTypes.add(fq);
            }
        }
        Set<String> seen = new HashSet<>();
        Set<String> ambiguous = new HashSet<>();
        for (JavaType.FullyQualified fq : qualifiedTypes) {
            for (JavaType.Variable member : fq.getMembers()) {
                if (!seen.add(member.getName())) {
                    ambiguous.add(member.getName());
                }
            }
        }
        return ambiguous;
    }

    private static final Set<String> JAVA_LANG_CLASS_NAMES = new HashSet<>(Arrays.asList(
            // java 8+
            "IllegalCallerException",
            "LayerInstantiationException",
            "Module",
            "ModuleLayer",
            "ProcessHandle",
            "Record",
            "ScopedValue",
            "StackWalker",
            "StringTemplate",
            "WrongThreadException",
            AbstractMethodError.class.getName(),
            Appendable.class.getName(),
            ArithmeticException.class.getName(),
            ArrayIndexOutOfBoundsException.class.getName(),
            ArrayStoreException.class.getName(),
            AssertionError.class.getName(),
            AutoCloseable.class.getName(),
            Boolean.class.getName(),
            BootstrapMethodError.class.getName(),
            Byte.class.getName(),
            CharSequence.class.getName(),
            Character.class.getName(),
            Class.class.getName(),
            ClassCastException.class.getName(),
            ClassCircularityError.class.getName(),
            ClassFormatError.class.getName(),
            ClassLoader.class.getName(),
            ClassNotFoundException.class.getName(),
            ClassValue.class.getName(),
            CloneNotSupportedException.class.getName(),
            Cloneable.class.getName(),
            Comparable.class.getName(),
            Deprecated.class.getName(),
            Double.class.getName(),
            Enum.class.getName(),
            EnumConstantNotPresentException.class.getName(),
            Error.class.getName(),
            Exception.class.getName(),
            ExceptionInInitializerError.class.getName(),
            Float.class.getName(),
            FunctionalInterface.class.getName(),
            IllegalAccessError.class.getName(),
            IllegalAccessException.class.getName(),
            IllegalArgumentException.class.getName(),
            IllegalMonitorStateException.class.getName(),
            IllegalStateException.class.getName(),
            IllegalThreadStateException.class.getName(),
            IncompatibleClassChangeError.class.getName(),
            IndexOutOfBoundsException.class.getName(),
            InheritableThreadLocal.class.getName(),
            InstantiationError.class.getName(),
            InstantiationException.class.getName(),
            Integer.class.getName(),
            InternalError.class.getName(),
            InterruptedException.class.getName(),
            Iterable.class.getName(),
            LinkageError.class.getName(),
            Long.class.getName(),
            Math.class.getName(),
            NegativeArraySizeException.class.getName(),
            NoClassDefFoundError.class.getName(),
            NoSuchFieldError.class.getName(),
            NoSuchFieldException.class.getName(),
            NoSuchMethodError.class.getName(),
            NoSuchMethodException.class.getName(),
            NullPointerException.class.getName(),
            Number.class.getName(),
            NumberFormatException.class.getName(),
            Object.class.getName(),
            OutOfMemoryError.class.getName(),
            Override.class.getName(),
            Package.class.getName(),
            Process.class.getName(),
            ProcessBuilder.class.getName(),
            Readable.class.getName(),
            ReflectiveOperationException.class.getName(),
            Runnable.class.getName(),
            Runtime.class.getName(),
            RuntimeException.class.getName(),
            RuntimePermission.class.getName(),
            SafeVarargs.class.getName(),
            SecurityException.class.getName(),
            SecurityManager.class.getName(),
            Short.class.getName(),
            StackOverflowError.class.getName(),
            StackTraceElement.class.getName(),
            StrictMath.class.getName(),
            String.class.getName(),
            StringBuffer.class.getName(),
            StringBuilder.class.getName(),
            StringIndexOutOfBoundsException.class.getName(),
            SuppressWarnings.class.getName(),
            System.class.getName(),
            Thread.class.getName(),
            ThreadDeath.class.getName(),
            ThreadGroup.class.getName(),
            ThreadLocal.class.getName(),
            Throwable.class.getName(),
            TypeNotPresentException.class.getName(),
            UnknownError.class.getName(),
            UnsatisfiedLinkError.class.getName(),
            UnsupportedClassVersionError.class.getName(),
            UnsupportedOperationException.class.getName(),
            VerifyError.class.getName(),
            VirtualMachineError.class.getName(),
            Void.class.getName()
    ));

    private static boolean conflictsWithJavaLang(J.Import elem) {
        return JAVA_LANG_CLASS_NAMES.contains(elem.getClassName());
    }

    private static class ImportUsage {
        final List<JRightPadded<J.Import>> imports = new ArrayList<>();
        boolean used = true;
    }
}
