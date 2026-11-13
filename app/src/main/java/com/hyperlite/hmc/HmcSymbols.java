package com.hyperlite.hmc;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XposedBridge;

/**
 * HMC 运行时符号解析。
 *
 * <p>HMC 的类名与方法名会随版本混淆/搬家（旧版把接口都挂在 Main 上，新版拆到了 ClockViews、
 * ClockRoom、Fingerprint 等类里），所以这里不写死单一签名：
 * <ol>
 *   <li>先按「名字 + 精确参数」找；</li>
 *   <li>名字还在但签名变了，就找同名、参数个数相同的重载；</li>
 *   <li>名字也被改了，就按「返回类型 + 参数类型」找唯一匹配；</li>
 *   <li>都找不到返回 null，由调用方跳过对应子功能。</li>
 * </ol>
 * 所有查找都吞掉异常：找不到只是子功能降级，绝不影响 SystemUI。
 */
final class HmcSymbols {

    static final String MAIN = "com.os4.musiccover.Main";
    static final String SETTINGS = "com.os4.musiccover.Settings";
    static final String CLOCK_VIEWS = "com.os4.musiccover.ClockViews";
    static final String CLOCK_COLLAPSE = "com.os4.musiccover.ClockCollapse";
    static final String CLOCK_ROOM = "com.os4.musiccover.ClockRoom";
    static final String FINGERPRINT = "com.os4.musiccover.Fingerprint";
    static final String NOTIFICATION_SINK = "com.os4.musiccover.island.NotificationSink";

    private HmcSymbols() {
    }

    /** 安全的 Class.forName：类不存在、loader 不对、类初始化失败都只返回 null。 */
    static Class<?> load(String name, ClassLoader loader) {
        if (name == null) return null;
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 在若干候选类里找静态/实例方法。
     *
     * @param owners 候选类名，按优先级排列（同一个成员可能在不同 HMC 版本里换了宿主类）
     * @param result 期望的返回类型，null 表示不校验
     * @param params 期望的参数类型，用于精确签名
     */
    static Method method(ClassLoader loader, String[] owners, String name,
                         Class<?> result, Class<?>... params) {
        if (owners == null || name == null) return null;
        for (String owner : owners) {
            Method found = methodIn(load(owner, loader), name, params);
            if (accepts(found, result) && Modifier.isStatic(found.getModifiers())) return found;
        }
        for (String owner : owners) {
            Method found = byName(load(owner, loader), name, params);
            if (accepts(found, result) && Modifier.isStatic(found.getModifiers())) return found;
        }
        for (String owner : owners) {
            Method found = byShape(load(owner, loader), result, params);
            if (found != null) return found;
        }
        return null;
    }

    /** 在单个类（含父类）里按名字 + 精确参数找方法；找不到返回 null。 */
    static Method methodIn(Class<?> owner, String name, Class<?>... params) {
        if (owner == null || name == null) return null;
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // 继续往父类找。
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** 找某个类（含父类）里唯一一个「无参且返回数字」的方法，用于 HMC 的设置读取器。 */
    static Method numericGetter(Class<?> owner, String preferred) {
        Method named = methodIn(owner, preferred);
        if (named != null && named.getParameterCount() == 0 && isNumeric(named.getReturnType())) {
            return named;
        }
        if (owner == null) return null;
        Method unique = null;
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || !isNumeric(method.getReturnType())) continue;
                if (unique != null) return null;
                method.setAccessible(true);
                unique = method;
            }
        }
        return unique;
    }

    /** 在若干候选类里找字段；找到后按名字优先、按类型兜底。 */
    static Field field(ClassLoader loader, String[] owners, String name, Class<?> type) {
        if (owners == null) return null;
        for (String owner : owners) {
            Field found = fieldIn(load(owner, loader), name);
            if (found != null && (type == null || sameType(found.getType(), type))) return found;
        }
        if (type == null) return null;
        for (String owner : owners) {
            Field found = uniqueStaticField(load(owner, loader), type);
            if (found != null) return found;
        }
        return null;
    }

    /** 在单个类（含父类）里按名字找字段。 */
    static Field fieldIn(Class<?> owner, String name) {
        if (owner == null || name == null) return null;
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找。
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    static Object staticValue(Field field) {
        if (field == null) return null;
        try {
            return field.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 旧 API 没有 deoptimize；方法存在就调一次，不存在静默跳过。 */
    static void deoptimize(Member member) {
        if (member == null) return;
        try {
            for (Method method : XposedBridge.class.getMethods()) {
                if (!"deoptimizeMethod".equals(method.getName()) || method.getParameterCount() != 1) continue;
                method.invoke(null, member);
                return;
            }
        } catch (Throwable ignored) {
            // 旧框架没有该能力，Hook 仍然生效，只是可能被内联绕过。
        }
    }

    static boolean isStatic(Method method) {
        return method != null && Modifier.isStatic(method.getModifiers());
    }

    static boolean isStatic(Field field) {
        return field != null && Modifier.isStatic(field.getModifiers());
    }

    static boolean isNumeric(Class<?> type) {
        return type == int.class || type == long.class || type == float.class || type == double.class
                || type == Integer.class || type == Long.class || type == Float.class || type == Double.class;
    }

    private static boolean accepts(Method method, Class<?> result) {
        if (method == null) return false;
        if (result == null) return true;
        Class<?> actual = method.getReturnType();
        if (actual.isPrimitive() || result.isPrimitive()) {
            return box(actual) == box(result);
        }
        return result.isAssignableFrom(actual);
    }

    private static boolean accepts(Field field, Class<?> type) {
        return field != null && (type == null || sameType(field.getType(), type));
    }

    private static boolean sameType(Class<?> first, Class<?> second) {
        return first == second || box(first) == box(second) || first.isAssignableFrom(second);
    }

    /** 同名、参数个数一致，参数类型允许父子类互换（HMC 混淆后常见 View/ViewGroup 之类的差别）。 */
    private static Method byName(Class<?> owner, String name, Class<?>[] params) {
        if (owner == null) return null;
        for (Method method : owner.getDeclaredMethods()) {
            if (!name.equals(method.getName())) continue;
            if (method.getParameterCount() != params.length) continue;
            if (!looseParameters(method.getParameterTypes(), params)) continue;
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    /** 名字不可用时的兜底：返回类型 + 参数类型唯一匹配（只认静态方法）。 */
    private static Method byShape(Class<?> owner, Class<?> result, Class<?>[] params) {
        if (owner == null || result == null || params == null) return null;
        Method unique = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) continue;
            if (!accepts(method, result)) continue;
            if (method.getParameterCount() != params.length) continue;
            if (!looseParameters(method.getParameterTypes(), params)) continue;
            if (unique != null) return null;
            unique = method;
        }
        if (unique != null) unique.setAccessible(true);
        return unique;
    }

    private static Field uniqueStaticField(Class<?> owner, Class<?> type) {
        if (owner == null) return null;
        Field unique = null;
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            if (!sameType(field.getType(), type)) continue;
            if (unique != null) return null;
            field.setAccessible(true);
            unique = field;
        }
        return unique;
    }

    private static boolean looseParameters(Class<?>[] declared, Class<?>[] wanted) {
        if (declared.length != wanted.length) return false;
        for (int i = 0; i < declared.length; i++) {
            if (!loose(declared[i], wanted[i])) return false;
        }
        return true;
    }

    private static boolean loose(Class<?> declared, Class<?> wanted) {
        if (declared == wanted) return true;
        if (declared.isPrimitive() || wanted.isPrimitive()) return box(declared) == box(wanted);
        return declared.isAssignableFrom(wanted) || wanted.isAssignableFrom(declared);
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
