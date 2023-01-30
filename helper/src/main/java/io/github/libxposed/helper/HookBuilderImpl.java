package io.github.libxposed.helper;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import dalvik.system.BaseDexClassLoader;
import io.github.libxposed.api.XposedInterface;


// Matcher <-> LazySequence --> List<Observer -> Result -> Observer -> Result ... >
@SuppressWarnings({"unused", "FieldCanBeLocal", "FieldMayBeFinal"})
public final class HookBuilderImpl implements HookBuilder {
    private final @NonNull XposedInterface ctx;
    private final @NonNull BaseDexClassLoader classLoader;

    private final @NonNull String sourcePath;

    private @Nullable MatchResultImpl matchResult;

    private @Nullable Predicate<Throwable> exceptionHandler = null;

    private boolean dexAnalysis = false;

    private boolean forceDexAnalysis = false;

    private boolean includeAnnotations = false;

    private final @NonNull ArrayList<StringMatcherImpl> stringMatchers = new ArrayList<>();

    private final @NonNull ArrayList<ClassMatcherImpl> classMatchers = new ArrayList<>();

    private final @NonNull ArrayList<FieldMatcherImpl> fieldMatchers = new ArrayList<>();

    private final @NonNull ArrayList<MethodMatcherImpl> methodMatchers = new ArrayList<>();

    private final @NonNull ArrayList<ConstructorMatcherImpl> constructorMatchers = new ArrayList<>();

    private ExecutorService executorService;

    interface Observer<T> {
        void onMatch(@NonNull T result);

        void onMiss();
    }

    class MatchResultImpl implements MatchResult {
        @NonNull
        @Override
        public Map<String, Class<?>> getMatchedClasses() {
            return null;
        }

        @NonNull
        @Override
        public Map<String, Field> getMatchedFields() {
            return null;
        }

        @NonNull
        @Override
        public Map<String, Method> getMatchedMethods() {
            return null;
        }

        @NonNull
        @Override
        public Map<String, Constructor<?>> getMatchedConstructors() {
            return null;
        }
    }

    public HookBuilderImpl(@NonNull XposedInterface ctx, @NonNull BaseDexClassLoader classLoader, @NonNull String sourcePath) {
        this.ctx = ctx;
        this.classLoader = classLoader;
        this.sourcePath = sourcePath;
    }

    @DexAnalysis
    @NonNull
    @Override
    public HookBuilder setForceDexAnalysis(boolean forceDexAnalysis) {
        this.forceDexAnalysis = forceDexAnalysis;
        return this;
    }

    @NonNull
    @Override
    public HookBuilder setExecutorService(@NonNull ExecutorService executorService) {
        this.executorService = executorService;
        return this;
    }

    @NonNull
    @Override
    public HookBuilder setLastMatchResult(@NonNull MatchResult matchResult) {
        this.matchResult = (MatchResultImpl) matchResult;
        return this;
    }

    @NonNull
    @Override
    public HookBuilder setExceptionHandler(@NonNull Predicate<Throwable> handler) {
        exceptionHandler = handler;
        return this;
    }

    abstract static class BaseMatcherImpl<Self extends BaseMatcherImpl<Self, Base, Reflect>, Base extends BaseMatcher<Base>, Reflect> implements BaseMatcher<Base> {
        protected boolean matchFirst;

        BaseMatcherImpl(boolean matchFirst) {
            this.matchFirst = matchFirst;
        }

    }

    @SuppressWarnings("unchecked")
    abstract class ReflectMatcherImpl<Self extends ReflectMatcherImpl<Self, Base, Reflect, SeqImpl>, Base extends ReflectMatcher<Base>, Reflect, SeqImpl extends LazySequenceImpl<?, ?, Reflect, ?>> extends BaseMatcherImpl<Self, Base, Reflect> implements ReflectMatcher<Base> {
        private final static int packageFlag = Modifier.PUBLIC | Modifier.PRIVATE | Modifier.PROTECTED;

        @Nullable
        protected String key = null;

        @Nullable
        private volatile SeqImpl lazySequence = null;

        protected int includeModifiers = 0; // (real & includeModifiers) == includeModifiers
        protected int excludeModifiers = 0; // (real & excludeModifiers) == 0

        protected volatile boolean pending = true;

        @NonNull
        protected final AtomicInteger leafCount = new AtomicInteger(1);

        private final Observer<?> dependencyCallback = new Observer<>() {
            @Override
            public void onMatch(@NonNull Object result) {
                leafCount.decrementAndGet();
            }

            @Override
            public void onMiss() {

            }
        };

        ReflectMatcherImpl(boolean matchFirst) {
            super(matchFirst);
        }

        protected final synchronized void ensureNotFinalized() {
            if (lazySequence != null) {
                throw new IllegalStateException("Cannot modify after finalized");
            }
        }

        final synchronized SeqImpl build() {
            return lazySequence = onBuild();
        }

        @NonNull
        @Override
        public final Base setMatchFirst(boolean matchFirst) {
            ensureNotFinalized();
            this.matchFirst = matchFirst;
            return (Base) this;
        }


        @NonNull
        @Override
        final public Base setKey(@NonNull String key) {
            ensureNotFinalized();
            pending = false;
            this.key = key;
            return (Base) this;
        }

        final protected void setModifier(boolean set, int flags) {
            ensureNotFinalized();
            if (set) {
                includeModifiers |= flags;
                excludeModifiers &= ~flags;
            } else {
                includeModifiers &= ~flags;
                excludeModifiers |= flags;
            }
        }

        @NonNull
        @Override
        final public Base setIsPublic(boolean isPublic) {
            setModifier(isPublic, Modifier.PUBLIC);
            return (Base) this;
        }

        @NonNull
        @Override
        final public Base setIsPrivate(boolean isPrivate) {
            setModifier(isPrivate, Modifier.PRIVATE);
            return (Base) this;
        }

        @NonNull
        @Override
        final public Base setIsProtected(boolean isProtected) {
            setModifier(isProtected, Modifier.PROTECTED);
            return (Base) this;
        }

        @NonNull
        @Override
        final public Base setIsPackage(boolean isPackage) {
            setModifier(!isPackage, packageFlag);
            return (Base) this;
        }

        @NonNull
        abstract SeqImpl onBuild();

        @NonNull
        <T extends BaseMatchImpl<T, U, RR, ?, ?>, U extends BaseMatch<U, RR, ?>, RR> T addDependency(@Nullable T field, @NonNull U input) {
            var in = (T) input;
            if (field != null) {
                in.removeObserver((Observer<RR>) dependencyCallback);
            } else {
                leafCount.incrementAndGet();
            }
            in.addObserver((Observer<RR>) dependencyCallback);
            return in;
        }

        @NonNull
        <T extends ContainerSyntaxImpl<M, ?, RR>, U extends ContainerSyntax<M>, M extends BaseMatch<M, RR, ?>, RR> T addDependencies(@Nullable T field, @NonNull U input) {
            var in = (T) input;
            // TODO
            if (field != null) {
                // ?
            }
//            input.addSupports(this);
            return in;
        }

        final void onMatch(@NonNull Collection<Reflect> matches) {
            var lazySequence = this.lazySequence;
            if (leafCount.get() != 1 || lazySequence == null) {
                throw new IllegalStateException("Illegal state when onMatch");
            }
            leafCount.decrementAndGet();
            if (!matches.iterator().hasNext()) {
                // TODO: on miss
                return;
            }
            lazySequence.onMatch(matches);
        }
    }

    @SuppressWarnings("unchecked")
    abstract class TypeMatcherImpl<Self extends TypeMatcherImpl<Self, Base, SeqImpl>, Base extends TypeMatcher<Base>, SeqImpl extends LazySequenceImpl<?, ?, Class<?>, ?>> extends ReflectMatcherImpl<Self, Base, Class<?>, SeqImpl> implements TypeMatcher<Base> {
        @Nullable
        protected ClassMatchImpl superClass = null;

        @Nullable
        protected StringMatchImpl name = null;

        @Nullable
        protected ContainerSyntaxImpl<ClassMatch, ?, Class<?>> containsInterfaces = null;

        TypeMatcherImpl(boolean matchFirst) {
            super(matchFirst);
        }

        @NonNull
        @Override
        public Base setName(@NonNull StringMatch name) {
            ensureNotFinalized();
            this.name = addDependency(this.name, name);
            return (Base) this;
        }

        @NonNull
        @Override
        public Base setSuperClass(@NonNull ClassMatch superClassMatch) {
            ensureNotFinalized();
            this.superClass = addDependency(this.superClass, superClassMatch);
            return (Base) this;
        }

        @NonNull
        @Override
        public Base setContainsInterfaces(@NonNull ContainerSyntax<ClassMatch> consumer) {
            ensureNotFinalized();
            this.containsInterfaces = addDependencies(this.containsInterfaces, consumer);
            return (Base) this;
        }

        @NonNull
        @Override
        public Base setIsAbstract(boolean isAbstract) {
            setModifier(isAbstract, Modifier.ABSTRACT);
            return (Base) this;
        }

        @NonNull
        @Override
        public Base setIsStatic(boolean isStatic) {
            setModifier(isStatic, Modifier.STATIC);
            return (Base) this;
        }

        @NonNull
        @Override
        public Base setIsFinal(boolean isFinal) {
            setModifier(isFinal, Modifier.FINAL);
            return (Base) this;
        }

        @NonNull
        @Override
        public Base setIsInterface(boolean isInterface) {
            setModifier(isInterface, Modifier.INTERFACE);
            return (Base) this;
        }
    }

    class ClassMatcherImpl extends TypeMatcherImpl<ClassMatcherImpl, ClassMatcher, ClassLazySequenceImpl> implements ClassMatcher {
        ClassMatcherImpl(boolean matchFirst) {
            super(matchFirst);
        }

        @NonNull
        @Override
        ClassLazySequenceImpl onBuild() {
            classMatchers.add(this);
            return new ClassLazySequenceImpl(this);
        }
    }

    class ParameterMatcherImpl extends TypeMatcherImpl<ParameterMatcherImpl, ParameterMatcher, ParameterLazySequenceImpl> implements ParameterMatcher {
        ParameterMatcherImpl(boolean matchFirst) {
            super(matchFirst);
        }

        @NonNull
        @Override
        ParameterLazySequenceImpl onBuild() {
            return new ParameterLazySequenceImpl(this);
        }

        protected int index = -1;

        @NonNull
        @Override
        public ParameterMatcher setIndex(int index) {
            ensureNotFinalized();
            this.index = index;
            return this;
        }
    }

    @SuppressWarnings("unchecked")
    abstract class MemberMatcherImpl<Self extends MemberMatcherImpl<Self, Base, Reflect, SeqImpl>, Base extends MemberMatcher<Base>, Reflect extends Member, SeqImpl extends LazySequenceImpl<?, ?, Reflect, ?>> extends ReflectMatcherImpl<Self, Base, Reflect, SeqImpl> implements MemberMatcher<Base> {
        @Nullable
        ClassMatchImpl declaringClass = null;

        MemberMatcherImpl(boolean matchFirst) {
            super(matchFirst);
        }

        @NonNull
        @Override
        final public Base setDeclaringClass(@NonNull ClassMatch declaringClassMatch) {
            ensureNotFinalized();
            this.declaringClass = addDependency(this.declaringClass, declaringClassMatch);
            return (Base) this;
        }

        @NonNull
        @Override
        final public Base setIsSynthetic(boolean isSynthetic) {
            setModifier(isSynthetic, 0x00001000);
            return (Base) this;
        }
    }

    final class FieldMatcherImpl extends MemberMatcherImpl<FieldMatcherImpl, FieldMatcher, Field, FieldLazySequenceImpl> implements HookBuilder.FieldMatcher {
        @Nullable
        private StringMatchImpl name = null;

        @Nullable
        private ClassMatchImpl type = null;

        FieldMatcherImpl(boolean matchFirst) {
            super(matchFirst);
        }

        @NonNull
        @Override
        FieldLazySequenceImpl onBuild() {
            fieldMatchers.add(this);
            return new FieldLazySequenceImpl(this);
        }

        @NonNull
        @Override
        public FieldMatcher setName(@NonNull StringMatch name) {
            ensureNotFinalized();
            this.name = addDependency(this.name, name);
            return this;
        }

        @NonNull
        @Override
        public FieldMatcher setType(@NonNull ClassMatch type) {
            ensureNotFinalized();
            this.type = addDependency(this.type, type);
            return this;
        }

        @NonNull
        @Override
        public FieldMatcher setIsStatic(boolean isStatic) {
            setModifier(isStatic, Modifier.STATIC);
            return this;
        }

        @NonNull
        @Override
        public FieldMatcher setIsFinal(boolean isFinal) {
            setModifier(isFinal, Modifier.FINAL);
            return this;
        }

        @NonNull
        @Override
        public FieldMatcher setIsTransient(boolean isTransient) {
            setModifier(isTransient, Modifier.TRANSIENT);
            return this;
        }

        @NonNull
        @Override
        public FieldMatcher setIsVolatile(boolean isVolatile) {
            setModifier(isVolatile, Modifier.VOLATILE);
            return this;
        }
    }

    @SuppressWarnings("unchecked")
    abstract class ExecutableMatcherImpl<Self extends ExecutableMatcherImpl<Self, Base, Reflect, SeqImpl>, Base extends ExecutableMatcher<Base>, Reflect extends Member, SeqImpl extends LazySequenceImpl<?, ?, Reflect, ?>> extends MemberMatcherImpl<Self, Base, Reflect, SeqImpl> implements ExecutableMatcher<Base> {
        protected int parameterCount = -1;

        @Nullable
        protected ContainerSyntaxImpl<ParameterMatch, ?, Class<?>> parameterTypes = null;

        @Nullable
        protected ContainerSyntaxImpl<StringMatch, ?, String> referredStrings = null;

        @Nullable
        protected ContainerSyntaxImpl<FieldMatch, ?, Field> assignedFields = null;

        @Nullable
        protected ContainerSyntaxImpl<FieldMatch, ?, Field> accessedFields = null;

        @Nullable
        protected ContainerSyntaxImpl<MethodMatch, ?, Method> invokedMethods = null;

        @Nullable
        protected ContainerSyntaxImpl<ConstructorMatch, ?, Constructor<?>> invokedConstructors = null;

        @Nullable
        protected byte[] opcodes = null;

        ExecutableMatcherImpl(boolean matchFirst) {
            super(matchFirst);
        }

        @NonNull
        @Override
        final public Base setParameterCount(int count) {
            ensureNotFinalized();
            this.parameterCount = count;
            return (Base) this;
        }

        @NonNull
        @Override
        final public Base setParameterTypes(@NonNull ContainerSyntax<ParameterMatch> parameterTypes) {
            ensureNotFinalized();
            this.parameterTypes = addDependencies(this.parameterTypes, parameterTypes);
            return (Base) this;
        }

        @DexAnalysis
        @NonNull
        @Override
        final public Base setReferredStrings(@NonNull ContainerSyntax<StringMatch> referredStrings) {
            ensureNotFinalized();
            dexAnalysis = true;
            this.referredStrings = addDependencies(this.referredStrings, referredStrings);
            return (Base) this;
        }

        @DexAnalysis
        @NonNull
        @Override
        final public Base setAssignedFields(@NonNull ContainerSyntax<FieldMatch> assignedFields) {
            ensureNotFinalized();
            dexAnalysis = true;
            this.assignedFields = addDependencies(this.assignedFields, assignedFields);
            return (Base) this;
        }

        @DexAnalysis
        @NonNull
        @Override
        final public Base setAccessedFields(@NonNull ContainerSyntax<FieldMatch> accessedFields) {
            ensureNotFinalized();
            dexAnalysis = true;
            this.accessedFields = addDependencies(this.accessedFields, accessedFields);
            return (Base) this;
        }

        @DexAnalysis
        @NonNull
        @Override
        final public Base setInvokedMethods(@NonNull ContainerSyntax<MethodMatch> invokedMethods) {
            ensureNotFinalized();
            dexAnalysis = true;
            this.invokedMethods = addDependencies(this.invokedMethods, invokedMethods);
            return (Base) this;
        }

        @DexAnalysis
        @NonNull
        @Override
        final public Base setInvokedConstructors(@NonNull ContainerSyntax<ConstructorMatch> invokedConstructors) {
            ensureNotFinalized();
            dexAnalysis = true;
            this.invokedConstructors = addDependencies(this.invokedConstructors, invokedConstructors);
            return (Base) this;
        }

        @DexAnalysis
        @NonNull
        @Override
        final public Base setContainsOpcodes(@NonNull byte[] opcodes) {
            ensureNotFinalized();
            dexAnalysis = true;
            this.opcodes = Arrays.copyOf(opcodes, opcodes.length);
            return (Base) this;
        }

        @NonNull
        @Override
        final public Base setIsVarargs(boolean isVarargs) {
            setModifier(isVarargs, 0x00000080);
            return (Base) this;
        }
    }

    final class MethodMatcherImpl extends ExecutableMatcherImpl<MethodMatcherImpl, MethodMatcher, Method, MethodLazySequenceImpl> implements MethodMatcher {
        private @Nullable StringMatchImpl name = null;

        private @Nullable ClassMatchImpl returnType = null;

        MethodMatcherImpl(boolean matchFirst) {
            super(matchFirst);
        }

        @NonNull
        @Override
        MethodLazySequenceImpl onBuild() {
            methodMatchers.add(this);
            return new MethodLazySequenceImpl(this);
        }

        @NonNull
        @Override
        public MethodMatcher setName(@NonNull StringMatch name) {
            ensureNotFinalized();
            this.name = addDependency(this.name, name);
            return this;
        }

        @NonNull
        @Override
        public MethodMatcher setReturnType(@NonNull ClassMatch returnType) {
            ensureNotFinalized();
            this.returnType = addDependency(this.returnType, returnType);
            return this;
        }

        @NonNull
        @Override
        public MethodMatcher setIsAbstract(boolean isAbstract) {
            setModifier(isAbstract, Modifier.ABSTRACT);
            return this;
        }

        @NonNull
        @Override
        public MethodMatcher setIsStatic(boolean isStatic) {
            setModifier(isStatic, Modifier.STATIC);
            return this;
        }

        @NonNull
        @Override
        public MethodMatcher setIsFinal(boolean isFinal) {
            setModifier(isFinal, Modifier.FINAL);
            return this;
        }

        @NonNull
        @Override
        public MethodMatcher setIsSynchronized(boolean isSynchronized) {
            setModifier(isSynchronized, Modifier.SYNCHRONIZED);
            return this;
        }

        @NonNull
        @Override
        public MethodMatcher setIsNative(boolean isNative) {
            setModifier(isNative, Modifier.NATIVE);
            return this;
        }
    }

    final class ConstructorMatcherImpl extends ExecutableMatcherImpl<ConstructorMatcherImpl, ConstructorMatcher, Constructor<?>, ConstructorLazySequenceImpl> implements ConstructorMatcher {
        ConstructorMatcherImpl(boolean matchFirst) {
            super(matchFirst);
        }

        @NonNull
        @Override
        ConstructorLazySequenceImpl onBuild() {
            constructorMatchers.add(this);
            return new ConstructorLazySequenceImpl(this);
        }
    }

    final static class StringMatcherImpl extends BaseMatcherImpl<StringMatcherImpl, StringMatcher, String> implements StringMatcher {
        @Nullable
        String exact = null;

        @Nullable
        String prefix = null;

        StringMatcherImpl(boolean matchFirst) {
            super(matchFirst);
        }

        @NonNull
        @Override
        public StringMatcher setExact(@NonNull String exact) {
            this.exact = exact;
            return this;
        }

        @NonNull
        @Override
        public StringMatcher setPrefix(@NonNull String prefix) {
            this.prefix = prefix;
            return this;
        }

        @NonNull
        @Override
        public StringMatcher setMatchFirst(boolean matchFirst) {
            this.matchFirst = matchFirst;
            return this;
        }
    }

    class ContainerSyntaxImpl<Match extends BaseMatch<Match, Reflect, ?>, MatchImpl extends BaseMatchImpl<MatchImpl, Match, Reflect, ?, ?>, Reflect> implements ContainerSyntax<Match> {
        class Operand {
            private @NonNull Object value;

            Operand(@NonNull MatchImpl match) {
                this.value = match;
            }

            Operand(@NonNull ContainerSyntax<Match> syntax) {
                this.value = syntax;
            }

            <M extends ReflectMatch<M, Reflect, ?>> Operand(@NonNull LazySequenceImpl<?, M, Reflect, ?> seq) {
                this.value = seq;
            }
        }

        abstract class Operands {
            final char operator;

            protected Operands(char operator) {
                this.operator = operator;
            }
        }

        class UnaryOperands extends Operands {
            final @NonNull Operand operand;

            UnaryOperands(@NonNull Operand operand, char operator) {
                super(operator);
                this.operand = operand;
            }
        }

        class BinaryOperands extends Operands {
            final @NonNull Operand left;
            final @NonNull Operand right;

            BinaryOperands(@NonNull Operand left, @NonNull Operand right, char operator) {
                super(operator);
                this.left = left;
                this.right = right;
            }
        }

        final protected @NonNull Operands operands;

        ContainerSyntaxImpl(@NonNull ContainerSyntax<Match> operand, char operator) {
            this.operands = new UnaryOperands(new Operand(operand), operator);
        }

        <M extends ReflectMatch<M, Reflect, ?>> ContainerSyntaxImpl(@NonNull LazySequenceImpl<?, M, Reflect, ?> operand, char operator) {
            this.operands = new UnaryOperands(new Operand(operand), operator);
        }

        ContainerSyntaxImpl(@NonNull MatchImpl operand, char operator) {
            this.operands = new UnaryOperands(new Operand(operand), operator);
        }

        ContainerSyntaxImpl(@NonNull ContainerSyntax<Match> left, @NonNull ContainerSyntax<Match> right, char operator) {
            this.operands = new BinaryOperands(new Operand(left), new Operand(right), operator);
        }

        @NonNull
        @Override
        public ContainerSyntax<Match> and(@NonNull ContainerSyntax<Match> predicate) {
            return new ContainerSyntaxImpl<>(this, predicate, '&');
        }

        @NonNull
        @Override
        public ContainerSyntax<Match> or(@NonNull ContainerSyntax<Match> predicate) {
            return new ContainerSyntaxImpl<>(this, predicate, '|');
        }

        @NonNull
        @Override
        public ContainerSyntax<Match> not() {
            return new ContainerSyntaxImpl<>(this, '!');
        }

        @SuppressWarnings("unchecked")
        private boolean operandTest(@NonNull Operand operand, @NonNull HashSet<Reflect> set, char operator) {
            if (operand.value instanceof ReflectMatchImpl) {
                return set.contains(((ReflectMatchImpl<?, ?, Reflect, ?, ?>) operand.value).match);
            } else if (operand.value instanceof StringMatchImpl) {
                // TODO
                return false;
            } else if (operand.value instanceof LazySequence) {
                var matches = ((LazySequenceImpl<?, ?, Reflect, ?>) operand.value).matches;
                if (matches == null) return false;
                if (operator == '^') {
                    for (var match : matches) {
                        if (!set.contains(match)) return false;
                    }
                    return true;
                } else if (operator == 'v') {
                    for (var match : matches) {
                        if (set.contains(match)) return true;
                    }
                }
                return false;
            } else {
                return ((ContainerSyntaxImpl<?, ?, Reflect>) operand.value).test(set);
            }
        }

        protected boolean test(@NonNull HashSet<Reflect> set) {
            if (operands instanceof ContainerSyntaxImpl.BinaryOperands) {
                BinaryOperands binaryOperands = (BinaryOperands) operands;
                var operator = binaryOperands.operator;
                boolean leftMatch = operandTest(binaryOperands.left, set, operator);
                if ((!leftMatch && operator == '&')) {
                    return false;
                } else if (leftMatch && operator == '|') {
                    return true;
                }
                return operandTest(binaryOperands.left, set, operator);
            } else if (operands instanceof ContainerSyntaxImpl.UnaryOperands) {
                UnaryOperands unaryOperands = (UnaryOperands) operands;
                var operator = unaryOperands.operator;
                boolean match = operandTest(unaryOperands.operand, set, operator);
                if (unaryOperands.operator == '!' || unaryOperands.operator == '-') {
                    return !match;
                } else if (unaryOperands.operator == '+') {
                    return match;
                }
            }
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    class LazySequenceImpl<Base extends LazySequence<Base, Match, Reflect, Matcher>, Match extends ReflectMatch<Match, Reflect, Matcher>, Reflect, Matcher extends ReflectMatcher<Matcher>> implements LazySequence<Base, Match, Reflect, Matcher> {
        @NonNull
        protected final BaseMatcherImpl<?, ?, Reflect> matcher;

        @Nullable
        protected volatile Iterable<Reflect> matches = null;

        @NonNull
        private final Object VALUE = new Object();

        @NonNull
        private final Map<Observer<Iterable<Reflect>>, Object> observers = new ConcurrentHashMap<>();

        LazySequenceImpl(@NonNull ReflectMatcherImpl<?, ?, Reflect, ?> matcher) {
            this.matcher = matcher;
        }

        @NonNull
        @Override
        final public Match first() {
            return null;
        }

        @NonNull
        @Override
        final public Match first(@NonNull Consumer<Matcher> consumer) {
            return null;
        }

        @NonNull
        @Override
        final public Base all(@NonNull Consumer<Matcher> consumer) {
            // TODO
            return (Base) this;
        }

        @NonNull
        @Override
        final public Base filter(@NonNull Predicate<Reflect> consumer) {
            // TODO
            return (Base) this;
        }

        @NonNull
        @Override
        final public Match pick(@NonNull MatchConsumer<Iterable<Reflect>, Reflect> consumer) {
            return null;
        }

        @NonNull
        @Override
        final public Base onMatch(@NonNull Consumer<Iterable<Reflect>> consumer) {
            // TODO
            return (Base) this;
        }

        @NonNull
        @Override
        final public ContainerSyntax<Match> conjunction() {
            return new ContainerSyntaxImpl<>(this, '^');
        }

        @NonNull
        @Override
        final public ContainerSyntax<Match> disjunction() {
            return new ContainerSyntaxImpl<>(this, 'v');
        }

        @NonNull
        @Override
        final public Base substituteIfMiss(@NonNull Supplier<Base> substitute) {
            return null;
        }

        @NonNull
        @Override
        final public Base matchIfMiss(@NonNull Consumer<Matcher> consumer) {
            return null;
        }

        @NonNull
        @Override
        final public <Bind extends LazyBind> Base bind(@NonNull Bind bind, @NonNull BiConsumer<Bind, Iterable<Reflect>> consumer) {
            // TODO
            return (Base) this;
        }

        final void onMatch(@NonNull Iterable<Reflect> matches) {
            // TODO
        }
    }

    class TypeLazySequenceImpl<Base extends TypeLazySequence<Base, Match, Matcher>, Match extends TypeMatch<Match, Matcher>, Matcher extends TypeMatcher<Matcher>> extends LazySequenceImpl<Base, Match, Class<?>, Matcher> implements TypeLazySequence<Base, Match, Matcher> {
        TypeLazySequenceImpl(TypeMatcherImpl<?, ?, ?> matcher) {
            super(matcher);
        }

        @NonNull
        @Override
        final public MethodLazySequence methods(@NonNull Consumer<MethodMatcher> matcher) {
            return null;
        }

        @NonNull
        @Override
        final public MethodMatch firstMethod(@NonNull Consumer<MethodMatcher> matcher) {
            return null;
        }

        @NonNull
        @Override
        final public ConstructorLazySequence constructors(@NonNull Consumer<ConstructorMatcher> matcher) {
            return null;
        }

        @NonNull
        @Override
        final public ConstructorMatch firstConstructor(@NonNull Consumer<ConstructorMatcher> matcher) {
            return null;
        }

        @NonNull
        @Override
        final public FieldLazySequence fields(@NonNull Consumer<FieldMatcher> matcher) {
            return null;
        }

        @NonNull
        @Override
        final public FieldMatch firstField(@NonNull Consumer<FieldMatcher> matcher) {
            return null;
        }
    }

    class ClassLazySequenceImpl extends TypeLazySequenceImpl<ClassLazySequence, ClassMatch, ClassMatcher> implements ClassLazySequence {
        ClassLazySequenceImpl(ClassMatcherImpl matcher) {
            super(matcher);
        }
    }

    class ParameterLazySequenceImpl extends TypeLazySequenceImpl<ParameterLazySequence, ParameterMatch, ParameterMatcher> implements ParameterLazySequence {
        ParameterLazySequenceImpl(ParameterMatcherImpl matcher) {
            super(matcher);
        }
    }

    class MemberLazySequenceImpl<Base extends MemberLazySequence<Base, Match, Reflect, Matcher>, Match extends MemberMatch<Match, Reflect, Matcher>, Reflect extends Member, Matcher extends MemberMatcher<Matcher>> extends LazySequenceImpl<Base, Match, Reflect, Matcher> implements MemberLazySequence<Base, Match, Reflect, Matcher> {
        MemberLazySequenceImpl(MemberMatcherImpl<?, ?, Reflect, ?> matcher) {
            super(matcher);
        }

        @NonNull
        @Override
        public ClassLazySequence declaringClasses(@NonNull Consumer<ClassMatcher> matcher) {
            return null;
        }

        @NonNull
        @Override
        public ClassMatch firstDeclaringClass(@NonNull Consumer<ClassMatcher> matcher) {
            return null;
        }
    }

    class FieldLazySequenceImpl extends MemberLazySequenceImpl<FieldLazySequence, FieldMatch, Field, FieldMatcher> implements FieldLazySequence {
        FieldLazySequenceImpl(FieldMatcherImpl matcher) {
            super(matcher);
        }

        @NonNull
        @Override
        public ClassLazySequence types(@NonNull Consumer<ClassMatcher> matcher) {
            return null;
        }

        @NonNull
        @Override
        public ClassMatch firstType(@NonNull Consumer<ClassMatcher> matcher) {
            return null;
        }
    }

    class ExecutableLazySequenceImpl<Base extends ExecutableLazySequence<Base, Match, Reflect, Matcher>, Match extends ExecutableMatch<Match, Reflect, Matcher>, Reflect extends Member, Matcher extends ExecutableMatcher<Matcher>> extends MemberLazySequenceImpl<Base, Match, Reflect, Matcher> implements ExecutableLazySequence<Base, Match, Reflect, Matcher> {
        ExecutableLazySequenceImpl(ExecutableMatcherImpl<?, ?, Reflect, ?> matcher) {
            super(matcher);
        }

        @NonNull
        @Override
        public ParameterLazySequence parameters(@NonNull Consumer<ParameterMatcher> matcher) {
            return null;
        }

        @NonNull
        @Override
        public ParameterMatch firstParameter(@NonNull Consumer<ParameterMatcher> matcher) {
            return null;
        }
    }

    class MethodLazySequenceImpl extends ExecutableLazySequenceImpl<MethodLazySequence, MethodMatch, Method, MethodMatcher> implements MethodLazySequence {
        MethodLazySequenceImpl(MethodMatcherImpl matcher) {
            super(matcher);
        }

        @NonNull
        @Override
        public ClassLazySequence returnTypes(@NonNull Consumer<ClassMatcher> matcher) {
            return null;
        }

        @NonNull
        @Override
        public ClassMatch firstReturnType(@NonNull Consumer<ClassMatcher> matcher) {
            return null;
        }
    }

    class ConstructorLazySequenceImpl extends ExecutableLazySequenceImpl<ConstructorLazySequence, ConstructorMatch, Constructor<?>, ConstructorMatcher> implements ConstructorLazySequence {
        ConstructorLazySequenceImpl(ConstructorMatcherImpl matcher) {
            super(matcher);
        }
    }

    @SuppressWarnings("unchecked")
    class BaseMatchImpl<Self extends BaseMatchImpl<Self, Base, Reflect, Matcher, MatcherImpl>, Base extends BaseMatch<Base, Reflect, Matcher>, Reflect, Matcher extends BaseMatcher<Matcher>, MatcherImpl extends BaseMatcherImpl<MatcherImpl, Matcher, Reflect>> implements BaseMatch<Base, Reflect, Matcher> {
        @NonNull
        protected final MatcherImpl matcher;

        @NonNull
        private final Object VALUE = new Object();

        @NonNull
        private final Map<Observer<Reflect>, Object> observers = new ConcurrentHashMap<>();

        protected BaseMatchImpl(@NonNull MatcherImpl matcher) {
            this.matcher = matcher;
        }

        @NonNull
        @Override
        public ContainerSyntax<Base> observe() {
            return new ContainerSyntaxImpl<>((Self) this, '+');
        }

        @NonNull
        @Override
        public ContainerSyntax<Base> reverse() {
            return new ContainerSyntaxImpl<>((Self) this, '-');
        }

        final void onMatch(Reflect reflect) {
            for (Observer<Reflect> observer : observers.keySet()) {
                observer.onMatch(reflect);
            }
        }

        final void addObserver(Observer<Reflect> observer) {
            observers.put(observer, VALUE);
        }

        final void removeObserver(Observer<Reflect> observer) {
            observers.remove(observer);
        }
    }

    @SuppressWarnings("unchecked")
    class ReflectMatchImpl<Self extends ReflectMatchImpl<Self, Base, Reflect, Matcher, MatcherImpl>, Base extends ReflectMatch<Base, Reflect, Matcher>, Reflect, Matcher extends ReflectMatcher<Matcher>, MatcherImpl extends ReflectMatcherImpl<MatcherImpl, Matcher, Reflect, ?>> extends BaseMatchImpl<Self, Base, Reflect, Matcher, MatcherImpl> implements ReflectMatch<Base, Reflect, Matcher> {
        @Nullable
        protected String key = null;

        @Nullable
        protected volatile Reflect match = null;

        protected ReflectMatchImpl(MatcherImpl matcher) {
            super(matcher);
        }

        @Nullable
        @Override
        final public String getKey() {
            return key;
        }

        @NonNull
        @Override
        public Base setKey(@Nullable String key) {
            this.key = key;
            if (key != null) {
                matcher.pending = false;
            }
            return (Base) this;
        }

        @NonNull
        @Override
        final public Base onMatch(@NonNull Consumer<Reflect> consumer) {
            matcher.pending = false;
            // TODO
            return (Base) this;
        }

        @NonNull
        @Override
        public Base substituteIfMiss(@NonNull Supplier<Base> replacement) {
            // TODO: not lazy
            return null;
        }

        @NonNull
        @Override
        public Base matchFirstIfMiss(@NonNull Consumer<Matcher> consumer) {
            return null;
        }

        @NonNull
        @Override
        public <Bind extends LazyBind> Base bind(@NonNull Bind bind, @NonNull BiConsumer<Bind, Reflect> consumer) {
            return (Base) this;
        }
    }

    class TypeMatchImpl<Self extends TypeMatchImpl<Self, Base, Matcher, MatcherImpl>, Base extends TypeMatch<Base, Matcher>, Matcher extends TypeMatcher<Matcher>, MatcherImpl extends TypeMatcherImpl<MatcherImpl, Matcher, ?>> extends ReflectMatchImpl<Self, Base, Class<?>, Matcher, MatcherImpl> implements TypeMatch<Base, Matcher> {
        protected TypeMatchImpl(MatcherImpl matcher) {
            super(matcher);
        }

        @NonNull
        @Override
        public StringMatch getName() {
            return null;
        }

        @NonNull
        @Override
        public ClassMatch getSuperClass() {
            return null;
        }

        @NonNull
        @Override
        public ClassLazySequence getInterfaces() {
            return null;
        }

        @NonNull
        @Override
        public MethodLazySequence getDeclaredMethods() {
            return null;
        }

        @NonNull
        @Override
        public ConstructorLazySequence getDeclaredConstructors() {
            return null;
        }

        @NonNull
        @Override
        public FieldLazySequence getDeclaredFields() {
            return null;
        }

        @NonNull
        @Override
        public ClassMatch getArrayType() {
            return null;
        }
    }

    class ClassMatchImpl extends TypeMatchImpl<ClassMatchImpl, ClassMatch, ClassMatcher, ClassMatcherImpl> implements ClassMatch {
        protected ClassMatchImpl(ClassMatcherImpl matcher) {
            super(matcher);
        }

        @NonNull
        @Override
        public ParameterMatch asParameter(int index) {
            return null;
        }
    }

    class ParameterMatchImpl extends TypeMatchImpl<ParameterMatchImpl, ParameterMatch, ParameterMatcher, ParameterMatcherImpl> implements ParameterMatch {
        protected ParameterMatchImpl(ParameterMatcherImpl matcher) {
            super(matcher);
        }
    }

    class MemberMatchImpl<Self extends MemberMatchImpl<Self, Base, Reflect, Matcher, MatcherImpl>, Base extends MemberMatch<Base, Reflect, Matcher>, Reflect extends Member, Matcher extends MemberMatcher<Matcher>, MatcherImpl extends MemberMatcherImpl<MatcherImpl, Matcher, Reflect, ?>> extends ReflectMatchImpl<Self, Base, Reflect, Matcher, MatcherImpl> implements MemberMatch<Base, Reflect, Matcher> {
        protected MemberMatchImpl(MatcherImpl matcher) {
            super(matcher);
        }

        @NonNull
        @Override
        final public ClassMatch getDeclaringClass() {
            return null;
        }
    }

    final class FieldMatchImpl extends MemberMatchImpl<FieldMatchImpl, FieldMatch, Field, FieldMatcher, FieldMatcherImpl> implements FieldMatch {
        FieldMatchImpl(FieldMatcherImpl matcher) {
            super(matcher);
        }

        @NonNull
        @Override
        public StringMatch getName() {
            return null;
        }

        @NonNull
        @Override
        public ClassMatch getType() {
            return null;
        }
    }

    class ExecutableMatchImpl<Self extends ExecutableMatchImpl<Self, Base, Reflect, Matcher, MatcherImpl>, Base extends ExecutableMatch<Base, Reflect, Matcher>, Reflect extends Member, Matcher extends ExecutableMatcher<Matcher>, MatcherImpl extends ExecutableMatcherImpl<MatcherImpl, Matcher, Reflect, ?>> extends MemberMatchImpl<Self, Base, Reflect, Matcher, MatcherImpl> implements ExecutableMatch<Base, Reflect, Matcher> {
        protected ExecutableMatchImpl(MatcherImpl matcher) {
            super(matcher);
        }

        @NonNull
        @Override
        final public ParameterLazySequence getParameterTypes() {
            return null;
        }

        @DexAnalysis
        @NonNull
        @Override
        final public FieldLazySequence getAssignedFields() {
            dexAnalysis = true;
            return null;
        }

        @DexAnalysis
        @NonNull
        @Override
        final public FieldLazySequence getAccessedFields() {
            dexAnalysis = true;
            return null;
        }

        @DexAnalysis
        @NonNull
        @Override
        final public MethodLazySequence getInvokedMethods() {
            dexAnalysis = true;
            return null;
        }

        @DexAnalysis
        @NonNull
        @Override
        final public ConstructorLazySequence getInvokedConstructors() {
            dexAnalysis = true;
            return null;
        }
    }

    final class MethodMatchImpl extends ExecutableMatchImpl<MethodMatchImpl, MethodMatch, Method, MethodMatcher, MethodMatcherImpl> implements MethodMatch {
        @Nullable
        StringMatchImpl name = null;

        @Nullable
        ClassMatchImpl returnType = null;

        MethodMatchImpl(MethodMatcherImpl matcher) {
            super(matcher);
        }

        @NonNull
        @Override
        public StringMatch getName() {
            return null;
        }

        @NonNull
        @Override
        public ClassMatch getReturnType() {
            return null;
        }
    }

    final class ConstructorMatchImpl extends ExecutableMatchImpl<ConstructorMatchImpl, ConstructorMatch, Constructor<?>, ConstructorMatcher, ConstructorMatcherImpl> implements ConstructorMatch {
        ConstructorMatchImpl(ConstructorMatcherImpl matcher) {
            super(matcher);
        }
    }

    final class StringMatchImpl extends BaseMatchImpl<StringMatchImpl, StringMatch, String, StringMatcher, StringMatcherImpl> implements StringMatch {
        StringMatchImpl(StringMatcherImpl matcher) {
            super(matcher);
        }
    }

    @NonNull
    @Override
    public MethodLazySequence methods(@NonNull Consumer<MethodMatcher> matcher) {
        return null;
    }

    @NonNull
    @Override
    public MethodMatch firstMethod(@NonNull Consumer<MethodMatcher> matcher) {
        return null;
    }

    @NonNull
    @Override
    public ConstructorLazySequence constructors(@NonNull Consumer<ConstructorMatcher> matcher) {
        return null;
    }

    @NonNull
    @Override
    public ConstructorMatch firstConstructor(@NonNull Consumer<ConstructorMatcher> matcher) {
        return null;
    }

    @NonNull
    @Override
    public FieldLazySequence fields(@NonNull Consumer<FieldMatcher> matcher) {
        return null;
    }

    @NonNull
    @Override
    public FieldMatch firstField(@NonNull Consumer<FieldMatcher> matcher) {
        return null;
    }

    @NonNull
    @Override
    public ClassLazySequence classes(@NonNull Consumer<ClassMatcher> matcher) {
        var impl = new ClassMatcherImpl(false);
        matcher.accept(impl);
        return null;
    }

    @NonNull
    @Override
    public ClassMatch firstClass(@NonNull Consumer<ClassMatcher> matcher) {
        var impl = new ClassMatcherImpl(true);
        matcher.accept(impl);
        return null;
    }

    @NonNull
    @Override
    public StringMatch string(@NonNull Consumer<StringMatcher> matcher) {
        return null;
    }

    @NonNull
    @Override
    public StringMatch exact(@NonNull String string) {
        return null;
    }

    @NonNull
    @Override
    public StringMatch prefix(@NonNull String prefix) {
        return null;
    }

    @NonNull
    @Override
    public ClassMatch exactClass(@NonNull String name) {
        return null;
    }

    @NonNull
    @Override
    public ClassMatch exact(@NonNull Class<?> clazz) {
        return null;
    }

    @NonNull
    @Override
    public MethodMatch exactMethod(@NonNull String signature) {
        return null;
    }

    @NonNull
    @Override
    public MethodMatch exact(@NonNull Method method) {
        return null;
    }

    @NonNull
    @Override
    public ConstructorMatch exactConstructor(@NonNull String signature) {
        return null;
    }

    @NonNull
    @Override
    public ConstructorMatch exact(@NonNull Constructor<?> constructor) {
        return null;
    }

    @NonNull
    @Override
    public FieldMatch exactField(@NonNull String signature) {
        return null;
    }

    @NonNull
    @Override
    public FieldMatch exact(@NonNull Field field) {
        return null;
    }

    @NonNull
    @Override
    public ParameterMatch exactParameter(@NonNull String signature) {
        return null;
    }

    @NonNull
    @Override
    public ParameterMatch exact(@NonNull Class<?>... params) {
        return null;
    }

    public @NonNull MatchResult build() {
        dexAnalysis = dexAnalysis || forceDexAnalysis;
        if (executorService == null) {
            executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        }
        if (dexAnalysis) {
            analysisDex();
        } else {
            analysisClassLoader();
        }
        return null;
    }

    private void analysisDex() {

    }

    // return first element that is greater than or equal to key
    private static <T extends Comparable<T>> int binarySearchLowerBound(final List<T> list, T key) {
        int low = 0, high = list.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int cmp = list.get(mid).compareTo(key);
            if (cmp < 0) low = mid + 1;
            else if (cmp > 0) high = mid - 1;
            else return mid;
        }
        return low;
    }

    private static <T extends Comparable<T>> ArrayList<T> merge(final List<T> a, final List<T> b) {
        ArrayList<T> res = new ArrayList<>(a.size() + b.size());
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            int cmp = a.get(i).compareTo(b.get(j));
            if (cmp < 0) res.add(a.get(i++));
            else if (cmp > 0) res.add(b.get(j++));
            else {
                res.add(a.get(i++));
                j++;
            }
        }
        res.addAll(0, a);
        while (i < a.size()) res.add(a.get(i++));
        while (j < b.size()) res.add(b.get(j++));
        return res;
    }

    private List<String> getAllClassNamesFromClassLoader() throws NoSuchFieldException, IllegalAccessException {
        List<String> res = new ArrayList<>();
        @SuppressWarnings("JavaReflectionMemberAccess") @SuppressLint("DiscouragedPrivateApi") var pathListField = BaseDexClassLoader.class.getDeclaredField("pathList");
        pathListField.setAccessible(true);
        var pathList = pathListField.get(classLoader);
        if (pathList == null) {
            throw new IllegalStateException("pathList is null");
        }
        var dexElementsField = pathList.getClass().getDeclaredField("dexElements");
        dexElementsField.setAccessible(true);
        var dexElements = (Object[]) dexElementsField.get(pathList);
        if (dexElements == null) {
            throw new IllegalStateException("dexElements is null");
        }
        for (var dexElement : dexElements) {
            var dexFileField = dexElement.getClass().getDeclaredField("dexFile");
            dexFileField.setAccessible(true);
            var dexFile = dexFileField.get(dexElement);
            if (dexFile == null) {
                continue;
            }
            var entriesField = dexFile.getClass().getDeclaredField("entries");
            entriesField.setAccessible(true);
            @SuppressWarnings("unchecked") var entries = (Enumeration<String>) entriesField.get(dexFile);
            if (entries == null) {
                continue;
            }
            // entries are sorted
            // perform O(N) merge so that we can have a sorted result and remove duplicates
            res = merge(res, Collections.list(entries));
        }
        return res;
    }

    private void joinAndClearTasks(List<Future<?>> tasks) {
        for (var task : tasks) {
            try {
                task.get();
            } catch (Throwable e) {
                @NonNull Throwable throwable = e;
                if (throwable instanceof ExecutionException && throwable.getCause() != null) {
                    throwable = throwable.getCause();
                }
                if (exceptionHandler != null) {
                    exceptionHandler.test(throwable);
                }
            }
        }
        tasks.clear();
    }

    private void analysisClassLoader() {
        final List<String> classNames;
        try {
            classNames = getAllClassNamesFromClassLoader();
        } catch (Throwable e) {
            if (exceptionHandler != null) {
                exceptionHandler.test(e);
            }
            return;
        }

        final boolean[] hasMatched = new boolean[]{false};
        do {
            // match class first
            final List<Future<?>> tasks = new ArrayList<>();
            for (final var classMatcher : classMatchers) {
                // not leaf
                if (classMatcher.leafCount.get() != 1) continue;
                // TODO: pending
                //       if (classMatcher.pending) continue;
                final var task = executorService.submit(() -> {
                    int low = 0, high = classNames.size() - 1;
                    if (classMatcher.name != null) {
                        final var nameMatcher = classMatcher.name.matcher;
                        if (nameMatcher.prefix != null) {
                            low = binarySearchLowerBound(classNames, nameMatcher.prefix);
                            high = binarySearchLowerBound(classNames, nameMatcher.prefix + Character.MAX_VALUE);
                        }
                        if (nameMatcher.exact != null) {
                            low = binarySearchLowerBound(classNames, nameMatcher.exact);
                            if (low < classNames.size() && classNames.get(low).equals(nameMatcher.exact)) {
                                high = low + 1;
                            } else {
                                low = high + 1;
                            }
                        }
                    }
                    final ArrayList<Class<?>> matches = new ArrayList<>();
                    for (int i = low; i < high && i < classNames.size(); i++) {
                        final var className = classNames.get(i);
                        // then check the rest conditions that need to load the class
                        final Class<?> theClass;
                        try {
                            theClass = Class.forName(className, false, classLoader);
                        } catch (ClassNotFoundException e) {
                            if (exceptionHandler != null) {
                                if (exceptionHandler.test(e)) {
                                    continue;
                                } else {
                                    break;
                                }
                            }
                            continue;
                        }
                        final var modifiers = theClass.getModifiers();
                        if ((modifiers & classMatcher.includeModifiers) != classMatcher.includeModifiers)
                            continue;
                        if ((modifiers & classMatcher.excludeModifiers) != 0) continue;
                        if (classMatcher.superClass != null) {
                            final var superClass = theClass.getSuperclass();
                            if (superClass == null || classMatcher.superClass.match != superClass)
                                continue;
                        }
                        if (classMatcher.containsInterfaces != null) {
                            final var ifArray = theClass.getInterfaces();
                            final var ifs = new HashSet<Class<?>>(ifArray.length);
                            Collections.addAll(ifs, ifArray);
                            if (!classMatcher.containsInterfaces.test(ifs)) continue;
                        }
                        matches.add(theClass);
                        if (classMatcher.matchFirst) {
                            break;
                        }
                    }
                    hasMatched[0] = hasMatched[0] || !matches.isEmpty();
                    classMatcher.onMatch(matches);
                });
                tasks.add(task);
            }
            joinAndClearTasks(tasks);

            for (final var fieldMatcher : fieldMatchers) {
                // not leaf
                if (fieldMatcher.leafCount.get() != 1) continue;

                final var task = executorService.submit(() -> {
                    final ArrayList<Class<?>> classList = new ArrayList<>();
                    if (fieldMatcher.declaringClass != null && fieldMatcher.declaringClass.match != null) {
                        classList.add(fieldMatcher.declaringClass.match);
                    } else {
                        // TODO
                    }

                    final ArrayList<Field> matches = new ArrayList<>();

                    for (final var theClass : classList) {
                        final var fields = theClass.getDeclaredFields();
                        for (final var field : fields) {
                            final var modifiers = field.getModifiers();
                            if ((modifiers & fieldMatcher.includeModifiers) != fieldMatcher.includeModifiers)
                                continue;
                            if ((modifiers & fieldMatcher.excludeModifiers) != 0) continue;
                            if (fieldMatcher.type != null && fieldMatcher.type.match != field.getType())
                                continue;
                            if (fieldMatcher.name != null) {
                                final var strMatcher = fieldMatcher.name.matcher;
                                if (strMatcher.prefix != null && !field.getName().startsWith(strMatcher.prefix))
                                    continue;
                                if (strMatcher.exact != null && !field.getName().equals(strMatcher.exact))
                                    continue;
                            }
                            matches.add(field);
                            if (fieldMatcher.matchFirst) {
                                break;
                            }
                        }
                    }
                    hasMatched[0] = hasMatched[0] || !matches.isEmpty();
                    fieldMatcher.onMatch(matches);
                });
                tasks.add(task);
            }
            joinAndClearTasks(tasks);

            for (var methodMatcher : methodMatchers) {
                // not leaf
                if (methodMatcher.leafCount.get() != 1) continue;

                var task = executorService.submit(() -> {
                    final ArrayList<Class<?>> classList = new ArrayList<>();
                    if (methodMatcher.declaringClass != null && methodMatcher.declaringClass.match != null) {
                        classList.add(methodMatcher.declaringClass.match);
                    } else {
                        // TODO
                    }

                    final ArrayList<Method> matches = new ArrayList<>();

                    for (final var clazz : classList) {
                        final var methods = clazz.getDeclaredMethods();
                        for (final var method : methods) {
                            final var modifiers = method.getModifiers();
                            if ((modifiers & methodMatcher.includeModifiers) != methodMatcher.includeModifiers)
                                continue;
                            if ((modifiers & methodMatcher.excludeModifiers) != 0) continue;
                            if (methodMatcher.returnType != null && methodMatcher.returnType.match != method.getReturnType())
                                continue;
                            if (methodMatcher.name != null) {
                                final var strMatcher = methodMatcher.name.matcher;
                                if (strMatcher.prefix != null && !method.getName().startsWith(strMatcher.prefix))
                                    continue;
                                if (strMatcher.exact != null && !method.getName().equals(strMatcher.exact))
                                    continue;
                            }
                            final var typeArrays = method.getParameterTypes();
                            if (methodMatcher.parameterCount >= 0 && methodMatcher.parameterCount != typeArrays.length)
                                continue;
                            if (methodMatcher.parameterTypes != null) {
                                final var parameterTypes = new HashSet<Class<?>>(typeArrays.length);
                                Collections.addAll(parameterTypes, typeArrays);
                                if (!methodMatcher.parameterTypes.test(parameterTypes)) continue;
                            }
                            matches.add(method);
                            if (methodMatcher.matchFirst) {
                                break;
                            }
                        }
                    }
                    hasMatched[0] = hasMatched[0] || !matches.isEmpty();
                    methodMatcher.onMatch(matches);
                });

                tasks.add(task);
            }
            joinAndClearTasks(tasks);

            for (var constructorMatcher : constructorMatchers) {
                // not leaf
                if (constructorMatcher.leafCount.get() != 1) continue;

                var task = executorService.submit(() -> {
                    final ArrayList<Class<?>> classList = new ArrayList<>();

                    if (constructorMatcher.declaringClass != null && constructorMatcher.declaringClass.match != null) {
                        classList.add(constructorMatcher.declaringClass.match);
                    } else {
                        // TODO
                    }

                    final ArrayList<Constructor<?>> matches = new ArrayList<>();

                    for (final var clazz : classList) {
                        final var constructors = clazz.getDeclaredConstructors();
                        for (final var constructor : constructors) {
                            final var modifiers = constructor.getModifiers();
                            if ((modifiers & constructorMatcher.includeModifiers) != constructorMatcher.includeModifiers)
                                continue;
                            if ((modifiers & constructorMatcher.excludeModifiers) != 0) continue;
                            final var typeArrays = constructor.getParameterTypes();
                            if (constructorMatcher.parameterCount >= 0 && constructorMatcher.parameterCount != typeArrays.length)
                                continue;
                            if (constructorMatcher.parameterTypes != null) {
                                final var parameterTypes = new HashSet<Class<?>>(typeArrays.length);
                                Collections.addAll(parameterTypes, typeArrays);
                                if (!constructorMatcher.parameterTypes.test(parameterTypes))
                                    continue;
                            }
                            matches.add(constructor);
                            if (constructorMatcher.matchFirst) {
                                break;
                            }
                        }
                    }
                    hasMatched[0] = hasMatched[0] || !matches.isEmpty();
                    constructorMatcher.onMatch(matches);
                });

                tasks.add(task);
            }
            joinAndClearTasks(tasks);
        } while (hasMatched[0]);
    }

}