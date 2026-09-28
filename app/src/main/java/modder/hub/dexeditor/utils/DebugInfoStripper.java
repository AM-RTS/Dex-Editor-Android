package modder.hub.dexeditor.utils;

import androidx.annotation.NonNull;

import com.android.tools.smali.dexlib2.iface.ClassDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class DebugInfoStripper implements com.android.tools.smali.dexlib2.iface.ClassDef {
    private final ClassDef delegate;
    private final ClassTree.CompilationOptions options;

    public DebugInfoStripper(ClassDef delegate, ClassTree.CompilationOptions options) {
        this.delegate = delegate;
        this.options = options;
    }

    @Override @javax.annotation.Nonnull public String getType() { return delegate.getType(); }
    @Override public int getAccessFlags() { return delegate.getAccessFlags(); }
    @Override @javax.annotation.Nullable public String getSuperclass() { return delegate.getSuperclass(); }
    @Override @javax.annotation.Nonnull public List<String> getInterfaces() { return delegate.getInterfaces(); }
    @Override @javax.annotation.Nullable public String getSourceFile() { return options.removeDebugSource || options.removeAllDebug ? null : delegate.getSourceFile(); }
    @Override @javax.annotation.Nonnull public Set<? extends com.android.tools.smali.dexlib2.iface.Annotation> getAnnotations() { return delegate.getAnnotations(); }
    @Override @javax.annotation.Nonnull public Iterable<? extends com.android.tools.smali.dexlib2.iface.Field> getStaticFields() { return delegate.getStaticFields(); }
    @Override @javax.annotation.Nonnull public Iterable<? extends com.android.tools.smali.dexlib2.iface.Field> getInstanceFields() { return delegate.getInstanceFields(); }
    @Override @javax.annotation.Nonnull public Iterable<? extends com.android.tools.smali.dexlib2.iface.Field> getFields() { return delegate.getFields(); }

    @Override @javax.annotation.Nonnull public Iterable<? extends com.android.tools.smali.dexlib2.iface.Method> getDirectMethods() {
        return wrapMethods(delegate.getDirectMethods());
    }

    @Override @javax.annotation.Nonnull public Iterable<? extends com.android.tools.smali.dexlib2.iface.Method> getVirtualMethods() {
        return wrapMethods(delegate.getVirtualMethods());
    }

    @Override @javax.annotation.Nonnull public Iterable<? extends com.android.tools.smali.dexlib2.iface.Method> getMethods() {
        return wrapMethods(delegate.getMethods());
    }

    @Override public int compareTo(@javax.annotation.Nonnull CharSequence o) { return delegate.compareTo(o); }
    @Override public void validateReference() throws com.android.tools.smali.dexlib2.iface.reference.Reference.InvalidReferenceException { delegate.validateReference(); }
    @Override public int length() { return delegate.length(); }
    @Override public char charAt(int index) { return delegate.charAt(index); }
    @NonNull
    @Override public CharSequence subSequence(int start, int end) { return delegate.subSequence(start, end); }
    @Override @javax.annotation.Nonnull public String toString() { return delegate.toString(); }

    private Iterable<? extends com.android.tools.smali.dexlib2.iface.Method> wrapMethods(Iterable<? extends com.android.tools.smali.dexlib2.iface.Method> methods) {
        List<com.android.tools.smali.dexlib2.iface.Method> wrapped = new ArrayList<>();
        for (com.android.tools.smali.dexlib2.iface.Method method : methods) {
            wrapped.add(new MethodStripper(method, options));
        }
        return wrapped;
    }
}

// Method stripper
final class MethodStripper implements com.android.tools.smali.dexlib2.iface.Method {
    private final com.android.tools.smali.dexlib2.iface.Method delegate;
    private final ClassTree.CompilationOptions options;

    public MethodStripper(com.android.tools.smali.dexlib2.iface.Method delegate, ClassTree.CompilationOptions options) {
        this.delegate = delegate;
        this.options = options;
    }

    @Override @javax.annotation.Nonnull public String getDefiningClass() { return delegate.getDefiningClass(); }
    @Override @javax.annotation.Nonnull public String getName() { return delegate.getName(); }
    @Override @javax.annotation.Nonnull public List<? extends com.android.tools.smali.dexlib2.iface.MethodParameter> getParameters() {
        if (options.removeDebugParam || options.removeAllDebug) {
            List<com.android.tools.smali.dexlib2.iface.MethodParameter> params = new ArrayList<>();
            for (com.android.tools.smali.dexlib2.iface.MethodParameter p : delegate.getParameters()) {
                params.add(new com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter(p.getType(), null, null));
            }
            return params;
        }
        return delegate.getParameters();
    }
    @Override @javax.annotation.Nonnull public List<? extends CharSequence> getParameterTypes() { return delegate.getParameterTypes(); }
    @Override @javax.annotation.Nonnull public String getReturnType() { return delegate.getReturnType(); }
    @Override public int getAccessFlags() { return delegate.getAccessFlags(); }
    @Override @javax.annotation.Nonnull public Set<? extends com.android.tools.smali.dexlib2.iface.Annotation> getAnnotations() { return delegate.getAnnotations(); }
    @Override @javax.annotation.Nonnull public Set<com.android.tools.smali.dexlib2.HiddenApiRestriction> getHiddenApiRestrictions() { return delegate.getHiddenApiRestrictions(); }
    @Override @javax.annotation.Nullable public com.android.tools.smali.dexlib2.iface.MethodImplementation getImplementation() {
        com.android.tools.smali.dexlib2.iface.MethodImplementation impl = delegate.getImplementation();
        if (impl == null) return null;
        return new MethodImplementationStripper(impl, options);
    }
    @Override public int compareTo(@javax.annotation.Nonnull com.android.tools.smali.dexlib2.iface.reference.MethodReference o) { return delegate.compareTo(o); }
    @Override public void validateReference() throws com.android.tools.smali.dexlib2.iface.reference.Reference.InvalidReferenceException { delegate.validateReference(); }
}

// method implemention stripper
final class MethodImplementationStripper implements com.android.tools.smali.dexlib2.iface.MethodImplementation {
    private final com.android.tools.smali.dexlib2.iface.MethodImplementation delegate;
    private final ClassTree.CompilationOptions options;

    public MethodImplementationStripper(com.android.tools.smali.dexlib2.iface.MethodImplementation delegate, ClassTree.CompilationOptions options) {
        this.delegate = delegate;
        this.options = options;
    }

    @Override public int getRegisterCount() { return delegate.getRegisterCount(); }
    @NonNull
    @Override public Iterable<? extends com.android.tools.smali.dexlib2.iface.instruction.Instruction> getInstructions() { return delegate.getInstructions(); }
    @NonNull
    @Override public List<? extends com.android.tools.smali.dexlib2.iface.TryBlock<? extends com.android.tools.smali.dexlib2.iface.ExceptionHandler>> getTryBlocks() { return delegate.getTryBlocks(); }

    @NonNull
    @Override public Iterable<? extends com.android.tools.smali.dexlib2.iface.debug.DebugItem> getDebugItems() {
        if (options.removeAllDebug) return new ArrayList<>();
        List<com.android.tools.smali.dexlib2.iface.debug.DebugItem> filtered = new ArrayList<>();
        for (com.android.tools.smali.dexlib2.iface.debug.DebugItem item : delegate.getDebugItems()) {
            boolean remove = false;
            switch (item.getDebugItemType()) {
                case com.android.tools.smali.dexlib2.DebugItemType.SET_SOURCE_FILE:
                    if (options.removeDebugSource) remove = true;
                    break;
                case com.android.tools.smali.dexlib2.DebugItemType.LINE_NUMBER:
                    if (options.removeDebugLine) remove = true;
                    break;
                case com.android.tools.smali.dexlib2.DebugItemType.PROLOGUE_END:
                    if (options.removeDebugPrologue) remove = true;
                    break;
                case com.android.tools.smali.dexlib2.DebugItemType.EPILOGUE_BEGIN:
                    // Prologue option usually covers epilogue too in some tools, or we can map it
                    if (options.removeDebugPrologue) remove = true;
                    break;
                case com.android.tools.smali.dexlib2.DebugItemType.START_LOCAL:
                case com.android.tools.smali.dexlib2.DebugItemType.END_LOCAL:
                case com.android.tools.smali.dexlib2.DebugItemType.RESTART_LOCAL:
                case com.android.tools.smali.dexlib2.DebugItemType.START_LOCAL_EXTENDED:
                    if (options.removeDebugLocal) remove = true;
                    break;
            }
            if (!remove) filtered.add(item);
        }
        // Param debug info is usually handled via method parameters if we want to strip names,
        // but in debug_info_item they are also present.
        // dexlib2 doesn't easily expose the parameter names list from debug_info_item here.
        return filtered;
    }
}
