package modder.hub.dexeditor.utils;

import com.android.tools.smali.dexlib2.iface.Annotation;
import com.android.tools.smali.dexlib2.iface.AnnotationElement;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.value.AnnotationEncodedValue;
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue;
import com.android.tools.smali.dexlib2.iface.value.EncodedValue;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Collects distinct string literals stored anywhere in a set of DEX classes. */
final class DexStringCollector {
    private DexStringCollector() {}

    static List<String> collect(Iterable<? extends ClassDef> classes) {
        Set<String> values = new HashSet<>();
        for (ClassDef classDef : classes) {
            for (Field field : classDef.getFields()) {
                EncodedValue initialValue = field.getInitialValue();
                if (initialValue != null) collectValue(initialValue, values);
                collectAnnotations(field.getAnnotations(), values);
            }

            for (Method method : classDef.getMethods()) {
                collectAnnotations(method.getAnnotations(), values);
                for (MethodParameter parameter : method.getParameters()) {
                    collectAnnotations(parameter.getAnnotations(), values);
                }
                MethodImplementation implementation = method.getImplementation();
                if (implementation == null) continue;
                for (Instruction instruction : implementation.getInstructions()) {
                    if (!(instruction instanceof ReferenceInstruction)) continue;
                    Reference reference = ((ReferenceInstruction) instruction).getReference();
                    if (reference instanceof StringReference) {
                        values.add(((StringReference) reference).getString());
                    }
                }
            }

            collectAnnotations(classDef.getAnnotations(), values);
        }

        List<String> result = new ArrayList<>(values);
        Collections.sort(result);
        return result;
    }

    private static void collectAnnotations(Set<? extends Annotation> annotations, Set<String> values) {
        if (annotations == null) return;
        for (Annotation annotation : annotations) {
            for (AnnotationElement element : annotation.getElements()) {
                collectValue(element.getValue(), values);
            }
        }
    }

    private static void collectValue(EncodedValue value, Set<String> values) {
        if (value instanceof StringEncodedValue) {
            values.add(((StringEncodedValue) value).getValue());
        } else if (value instanceof AnnotationEncodedValue) {
            for (AnnotationElement element : ((AnnotationEncodedValue) value).getElements()) {
                collectValue(element.getValue(), values);
            }
        } else if (value instanceof ArrayEncodedValue) {
            for (EncodedValue nested : ((ArrayEncodedValue) value).getValue()) {
                collectValue(nested, values);
            }
        }
    }
}
