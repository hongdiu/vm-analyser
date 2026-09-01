package com.analyzer.parser;

import org.eclipse.core.runtime.IContributor;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.spi.RegistryStrategy;
import org.eclipse.core.runtime.RegistryFactory;
import org.eclipse.core.runtime.ContributorFactorySimple;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

/**
 * MAT 运行时引导（脱离 OSGi 框架的 "plain" 模式初始化）。
 *
 * <p>MAT 的快照工厂（SnapshotFactory）通过 Equinox 扩展点
 * {@code org.eclipse.mat.api.factory} 定位 {@code SnapshotFactoryImpl}，
 * HPROF 解析器（HprofIndexBuilder）又通过 {@code org.eclipse.mat.parser.parser}
 * 扩展点注册。在普通 java -jar 环境下，Equinox 注册表可能没有自动扫描
 * classpath 上各 jar 的 plugin.xml，这里做兜底：
 * 用 RegistryFactory 的 plain 注册表逐个 addContribution(plugin.xml)，
 * 并按“扩展点声明者优先”的顺序装载、必要时重复一轮。</p>
 */
final class MatRuntimeBootstrap {

    /** MAT 工厂扩展点，存在且有实现即说明注册就绪。 */
    private static final String FACTORY_POINT = "org.eclipse.mat.api.factory";

    /** plain 注册表的访问令牌（createRegistry / addContribution 必须一致）。 */
    private static final Object REGISTRY_TOKEN = new Object();

    private static volatile boolean ready;
    private static final boolean DEBUG = Boolean.getBoolean("jvmha.debug.boot");

    private MatRuntimeBootstrap() {
    }

    static synchronized void ensureRegistryReady() {
        if (ready) {
            return;
        }
        IExtensionRegistry registry;
        try {
            // plain 模式下没有 OSGi Activator 注册 provider，这里通常拿到 null
            registry = RegistryFactory.getRegistry();
        } catch (Throwable t) {
            registry = null;
        }
        if (registry == null) {
            // 显式创建 plain 注册表：base RegistryStrategy + 空存储位置，
            // createRegistry 同时把它装为全局默认 provider（Platform 后续可见）
            RegistryStrategy strategy = new RegistryStrategy(new java.io.File[0],
                    new boolean[0]);
            // 第二个参数是“访问令牌”，后续 addContribution 必须传同一个对象
            registry = RegistryFactory.createRegistry(strategy, REGISTRY_TOKEN, null);
            // createRegistry 只“造”不“装”：必须显式装为全局默认 provider，
            // 否则 MAT 内部 Platform.getExtensionRegistry() 仍会拿到 null
            try {
                RegistryFactory.setDefaultRegistryProvider(
                        new org.eclipse.core.internal.registry.osgi.RegistryProviderOSGI(registry));
            } catch (org.eclipse.core.runtime.CoreException alreadySet) {
                // provider 已存在（理论上不会走到这里），忽略
            }
        }
        // 同样问题也存在于 IContentTypeManager：Platform 只认 OSGi ServiceTracker，
        // plain 模式下为 null，而 MAT 的快照工厂靠 content-type 识别 hprof 格式。
        installContentTypeManager();
        if (!hasFactory(registry)) {
            // 收集扩展描述文件：优先 fat-jar 布局（eclipse-plugin/<bundle>.xml，
            // 由 build.gradle patchMatLibs 任务生成），回退到散装 jar 的 plugin.xml
            List<URL> pluginXmls = collectPluginXmls();
            // 两遍装载：第一遍可能因扩展点尚未声明而丢失部分扩展，第二遍补齐
            for (int pass = 0; pass < 2 && !hasFactory(registry); pass++) {
                for (URL url : pluginXmls) {
                    try (InputStream in = url.openStream()) {
                        // Equinox 的扩展点全名 = 贡献者名 + "." + 声明的 id，
                        // 因此贡献者必须用 bundle 符号名（jar 名的版本前缀部分）
                        IContributor contributor =
                                ContributorFactorySimple.createContributor(bundleName(url));
                        registry.addContribution(in, contributor, false,
                                url.toExternalForm(), null, REGISTRY_TOKEN);
                    } catch (Throwable t) {
                        if (DEBUG) {
                            System.out.println("[boot] addContribution failed: " + url + " -> " + t);
                        }
                        // 单个描述文件解析失败（如引用了缺失的类）不影响整体
                    }
                }
            }
        }
        // 同样问题也存在于 IContentTypeManager：Platform 只认 OSGi ServiceTracker，
        // plain 模式下为 null，而 MAT 的快照工厂靠 content-type 识别 hprof 格式。
        installContentTypeManager();
        installPlatformContext();
        // MAT 的解析器注册表由 ParserPlugin/HprofPlugin 等 Activator 在 OSGi 启动时
        // 初始化；plain 模式没人调用 start()，这里在扩展装载完成后手动补一次。
        startMatPlugins();
        if (!hasFactory(registry)) {
            throw new IllegalStateException(
                    "Eclipse MAT extension registry bootstrap failed: "
                            + FACTORY_POINT + " is empty outside OSGi. "
                            + "Check that the org.eclipse.mat.* jars are on the classpath.");
        }
        ready = true;
    }

    private static boolean hasFactory(IExtensionRegistry registry) {
        return registry.getExtensionPoint(FACTORY_POINT) != null
                && registry.getExtensionPoint(FACTORY_POINT).getExtensions().length > 0;
    }

    /** fat-jar 布局下已知的 bundle 清单（扩展点声明者优先装载） */
    private static final String[] KNOWN_BUNDLES = {
            "org.eclipse.mat.api",          // 声明 api.factory / heapDumpProvider 等扩展点
            "org.eclipse.mat.report",      // 声明 query/report 扩展点
            "org.eclipse.mat.parser",      // 注册 api.factory 实现 + parser 扩展点
            "org.eclipse.mat.hprof",       // 注册 hprof parser / 内容类型
            "org.eclipse.core.runtime",
            "org.eclipse.core.contenttype",
            "org.eclipse.equinox.app",
            "org.eclipse.equinox.registry",
            "org.eclipse.equinox.preferences",
            "org.eclipse.core.jobs",
            "org.eclipse.osgi",
    };

    private static List<URL> collectPluginXmls() {
        List<URL> urls = new ArrayList<>();
        ClassLoader cl = MatRuntimeBootstrap.class.getClassLoader();
        try {
            // 1) fat-jar 布局：按固定清单取
            for (String bundle : KNOWN_BUNDLES) {
                URL u = cl.getResource("eclipse-plugin/" + bundle + ".xml");
                if (u != null) {
                    urls.add(u);
                }
            }
            if (!urls.isEmpty()) {
                return urls; // 清单顺序即装载顺序
            }
            // 2) 回退：散装 jar 的根 plugin.xml（按 rank 排序保证扩展点优先）
            Enumeration<URL> e = cl.getResources("plugin.xml");
            while (e.hasMoreElements()) {
                urls.add(e.nextElement());
            }
            urls.sort(Comparator.comparingInt(MatRuntimeBootstrap::rank).reversed());
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot scan plugin.xml on classpath", ex);
        }
        return urls;
    }

    /**
     * 把 ContentTypeManager 单例注入 InternalPlatform 的 contentTracker 字段。
     * ServiceTracker 的公开构造器都要求非空 BundleContext（plain 模式没有框架），
     * 因此用 Unsafe 分配一个只重写了 getService() 的子类实例（不调用任何构造器）。
     */
    @SuppressWarnings({"restriction", "unchecked"})
    private static void installContentTypeManager() {
        try {
            Class<?> ip = Class.forName("org.eclipse.core.internal.runtime.InternalPlatform");
            Object def = ip.getMethod("getDefault").invoke(null);
            java.lang.reflect.Field trackerField = ip.getDeclaredField("contentTracker");
            trackerField.setAccessible(true);
            if (trackerField.get(def) != null) {
                return; // 已就绪
            }
            // equinox 正版 ContentTypeManager 在 plain 模式建目录时会触发
            // OSGi ServiceCaller（必炸），改用自研最小实现（覆盖 MAT 的格式识别需求）
            CONTENT_TYPE_HOLDER = new SimpleContentTypeManager();
            org.eclipse.core.runtime.content.IContentTypeManager mgr = CONTENT_TYPE_HOLDER;
            Object stub = unsafe().allocateInstance(ContentTypeTrackerStub.class);
            trackerField.set(def, stub);
            if (DEBUG) {
                System.out.println("[boot] content type manager installed: " + mgr);
            }
            // Equinox Preferences（ContentTypeManager/MAT 解析选项会用到）依赖
            // install/configuration/instance/user 四类 Location，plain 模式同样为空。
            // 统一注入一个临时目录桩即可。
            installLocations();
        } catch (Throwable t) {
            if (DEBUG) {
                System.out.println("[boot] content type manager install failed: " + t);
            }
        }
    }

    /** 给 InternalPlatform 的 4 个 Location tracker 字段注入临时目录桩。 */
    private static void installLocations() throws Exception {
        if (LOCATION_HOLDER == null) {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("jvm-ha-eclipse");
            LOCATION_HOLDER = new TempLocation(root.toUri().toURL());
        }
        Class<?> ip = Class.forName("org.eclipse.core.internal.runtime.InternalPlatform");
        Object def = ip.getMethod("getDefault").invoke(null);
        for (String field : new String[]{"installLocation", "configurationLocation",
                "instanceLocation", "userLocation"}) {
            try {
                java.lang.reflect.Field f = ip.getDeclaredField(field);
                f.setAccessible(true);
                if (f.get(def) == null) {
                    sun.misc.Unsafe u = unsafe();
                    f.set(def, u.allocateInstance(LocationTrackerStub.class));
                }
            } catch (NoSuchFieldException ignore) {
                // 版本差异：字段不存在就跳过
            }
        }
        // DataArea（.metadata 存放点）：预置 location 并标记 initialized，
        // 让 assertLocationInitialized() 直接早退，绕开 Activator/BundleContext 依赖。
        try {
            Class<?> mdk = Class.forName("org.eclipse.core.internal.runtime.MetaDataKeeper");
            Object area = mdk.getMethod("getMetaArea").invoke(null);
            java.nio.file.Path dir = java.nio.file.Paths.get(LOCATION_HOLDER.getURL().toURI());
            java.lang.reflect.Field loc = area.getClass().getDeclaredField("location");
            loc.setAccessible(true);
            loc.set(area, org.eclipse.core.runtime.Path.fromOSString(dir.toString()));
            java.lang.reflect.Field ini = area.getClass().getDeclaredField("initialized");
            ini.setAccessible(true);
            ini.setBoolean(area, true);
        } catch (Throwable t) {
            if (DEBUG) {
                System.out.println("[boot] DataArea pre-init failed: " + t);
            }
        }
        // Equinox Preferences 自己还有一组 configuration/instance Location 的
        // ServiceTracker（plain 模式下 getService() 返回 null），同样替换成桩。
        try {
            Class<?> utils = Class.forName("org.eclipse.core.internal.preferences.PreferencesOSGiUtils");
            Object singleton = utils.getMethod("getDefault").invoke(null);
            for (String field : new String[]{"configurationLocationTracker", "instanceLocationTracker"}) {
                try {
                    java.lang.reflect.Field f = utils.getDeclaredField(field);
                    f.setAccessible(true);
                    f.set(singleton, unsafe().allocateInstance(LocationTrackerStub.class));
                } catch (NoSuchFieldException ignore) {
                    // 版本差异
                }
            }
        } catch (Throwable t) {
            if (DEBUG) {
                System.out.println("[boot] preferences location trackers not patched: " + t);
            }
        }
        // Platform.getPreferencesService() 也只认 ServiceTracker：注入 PreferencesService 单例
        try {
            Class<?> ip2 = Class.forName("org.eclipse.core.internal.runtime.InternalPlatform");
            Object def2 = ip2.getMethod("getDefault").invoke(null);
            java.lang.reflect.Field pt = ip2.getDeclaredField("preferencesTracker");
            pt.setAccessible(true);
            if (pt.get(def2) == null) {
                Class<?> ps = Class.forName("org.eclipse.core.internal.preferences.PreferencesService");
                PREFERENCES_HOLDER = ps.getMethod("getDefault").invoke(null);
                pt.set(def2, unsafe().allocateInstance(PreferencesTrackerStub.class));
            }
        } catch (Throwable t) {
            if (DEBUG) {
                System.out.println("[boot] preferences service not patched: " + t);
            }
        }
    }

    private static volatile Object PREFERENCES_HOLDER;

    /** PreferencesService 单例的 ServiceTracker 桩。 */
    public static final class PreferencesTrackerStub
            extends org.osgi.util.tracker.ServiceTracker<
                    org.eclipse.core.runtime.preferences.IPreferencesService,
                    org.eclipse.core.runtime.preferences.IPreferencesService> {
        private PreferencesTrackerStub() {
            super(null, (String) null, null); // 永不被调用（allocateInstance 绕过）
        }

        @Override
        public org.eclipse.core.runtime.preferences.IPreferencesService getService() {
            @SuppressWarnings("unchecked")
            org.eclipse.core.runtime.preferences.IPreferencesService svc =
                    (org.eclipse.core.runtime.preferences.IPreferencesService) PREFERENCES_HOLDER;
            return svc;
        }
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        return (sun.misc.Unsafe) uf.get(null);
    }

    /** 全部服务方法返回同一个临时目录 Location 的 ServiceTracker 桩。 */
    public static final class LocationTrackerStub
            extends org.osgi.util.tracker.ServiceTracker<
                    org.eclipse.osgi.service.datalocation.Location,
                    org.eclipse.osgi.service.datalocation.Location> {
        private LocationTrackerStub() {
            super(null, (String) null, null); // 永不被调用（allocateInstance 绕过）
        }

        @Override
        public org.eclipse.osgi.service.datalocation.Location getService() {
            return LOCATION_HOLDER;
        }
    }

    /** 极简文件目录 Location 实现（只为喂给 Equinox Preferences）。 */
    private static final class TempLocation
            implements org.eclipse.osgi.service.datalocation.Location {
        private final java.net.URL url;

        TempLocation(java.net.URL url) {
            this.url = url;
        }

        @Override public boolean allowsDefault() { return false; }
        @Override public java.net.URL getDefault() { return null; }
        @Override public org.eclipse.osgi.service.datalocation.Location getParentLocation() { return null; }
        @Override public java.net.URL getURL() { return url; }
        @Override public boolean isSet() { return true; }
        @Override public boolean isReadOnly() { return false; }
        @Override public boolean setURL(java.net.URL u, boolean lock) { return true; }
        @Override public boolean set(java.net.URL u, boolean lock) { return true; }
        @Override public boolean set(java.net.URL u, boolean lock, String name) { return true; }
        @Override public boolean lock() { return true; }
        @Override public void release() { /* 无锁实现 */ }
        @Override public boolean isLocked() { return false; }
        @Override public org.eclipse.osgi.service.datalocation.Location createLocation(
                org.eclipse.osgi.service.datalocation.Location parent, java.net.URL u, boolean ro) {
            return new TempLocation(u);
        }
        @Override public java.net.URL getDataArea(String bundleName) throws java.io.IOException {
            try {
                java.nio.file.Path dir = java.nio.file.Paths.get(url.toURI())
                        .resolve(bundleName.replace('/', '_'));
                java.nio.file.Files.createDirectories(dir);
                return dir.toUri().toURL();
            } catch (java.net.URISyntaxException e) {
                throw new java.io.IOException("bad temp location", e);
            }
        }
    }

    static {
        // no-op：确保类加载顺序稳定
    }

    /** 供 ContentTypeTrackerStub / LocationTrackerStub.getService() 返回（静态注入）。 */
    private static volatile org.eclipse.core.runtime.content.IContentTypeManager CONTENT_TYPE_HOLDER;
    private static volatile org.eclipse.osgi.service.datalocation.Location LOCATION_HOLDER;

    /** 只重写 getService() 的 ServiceTracker 桩，实例经 Unsafe 分配、不跑构造器。 */
    public static final class ContentTypeTrackerStub
            extends org.osgi.util.tracker.ServiceTracker<
                    org.eclipse.core.runtime.content.IContentTypeManager,
                    org.eclipse.core.runtime.content.IContentTypeManager> {
        private ContentTypeTrackerStub() {
            super(null, (String) null, null); // 永不被调用（allocateInstance 绕过）
        }

        @Override
        public org.eclipse.core.runtime.content.IContentTypeManager getService() {
            return CONTENT_TYPE_HOLDER;
        }
    }

    /**
     * 手工“启动” MAT 的 Plugin 单例（equinox Activator 模式在 plain 下无人调用 start，
     * 而 Plugin.start 又依赖 BundleContext）。做法：反射 new 出实例、
     * 赋给各自的静态 singleton 字段，并按字段类型补齐 ExtensionTracker /
     * ParserRegistry 等启动期依赖。
     */
    private static void startMatPlugins() {
        String[][] candidates = {
                {"org.eclipse.mat.parser.internal.ParserPlugin", "org.eclipse.mat.parser"},
                {"org.eclipse.mat.hprof.HprofPlugin", "org.eclipse.mat.hprof"},
                {"org.eclipse.mat.internal.MATPlugin", "org.eclipse.mat.api"},
        };
        for (String[] cand : candidates) {
            String name = cand[0];
            String symbolic = cand[1];
            try {
                Class<?> c = Class.forName(name, false, MatRuntimeBootstrap.class.getClassLoader());
                java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                Object inst = ctor.newInstance();
                Object tracker = null;
                // 先建 ExtensionTracker（MAT 用它监听扩展变化）
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    f.setAccessible(true);
                    if (org.eclipse.core.runtime.dynamichelpers.IExtensionTracker.class
                            .isAssignableFrom(f.getType())) {
                        tracker = new org.eclipse.core.runtime.dynamichelpers.ExtensionTracker(
                                Platform.getExtensionRegistry());
                        f.set(inst, tracker);
                    }
                }
                // 再建各类 Registry（构造参数是 tracker 或无参）
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    f.setAccessible(true);
                    String tn = f.getType().getName();
                    if (tn.endsWith("Registry") && !"java.lang.Object".equals(tn)) {
                        try {
                            java.lang.reflect.Constructor<?> rc =
                                    f.getType().getDeclaredConstructor(
                                            org.eclipse.core.runtime.dynamichelpers.IExtensionTracker.class);
                            rc.setAccessible(true);
                            f.set(inst, rc.newInstance(tracker));
                        } catch (NoSuchMethodException noArg) {
                            java.lang.reflect.Constructor<?> rc =
                                    f.getType().getDeclaredConstructor();
                            rc.setAccessible(true);
                            f.set(inst, rc.newInstance());
                        }
                    }
                }
                // 最后赋静态 singleton（plugin / plural 命名惯例）
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            && f.getType().equals(c)) {
                        f.setAccessible(true);
                        if (f.get(null) == null) {
                            f.set(null, inst);
                        }
                    }
                }
                // Plugin.bundle 字段：MAT 部分代码用 getBundle().getSymbolicName()，
                // 注入一个动态代理 Bundle（只需支撑几个只读方法）
                try {
                    java.lang.reflect.Field bf =
                            org.eclipse.core.runtime.Plugin.class.getDeclaredField("bundle");
                    bf.setAccessible(true);
                    if (bf.get(inst) == null) {
                        bf.set(inst, newBundleProxy(symbolic));
                    }
                } catch (NoSuchFieldException ignore) {
                }
                // Plugin 基类内部的 plural 单例（getDefault() 走它）
                try {
                    java.lang.reflect.Field plural =
                            org.eclipse.core.runtime.Plugin.class.getDeclaredField("plural");
                    plural.setAccessible(true);
                    if (plural.get(null) == null) {
                        plural.set(null, inst);
                    }
                } catch (NoSuchFieldException ignore) {
                    // equinox 版本无此字段则跳过
                }
                if (DEBUG) {
                    System.out.println("[boot] started " + name);
                }
            } catch (ClassNotFoundException notFound) {
                // 该版本无此类，跳过
            } catch (Throwable t) {
                if (DEBUG) {
                    System.out.println("[boot] start " + name + " failed: " + t);
                }
            }
        }
    }

    /**
     * PlatformActivator.context：MAT 的 hprof 解析器会调 Platform.inDebugMode()，
     * 它直接读该静态 BundleContext。注入一个只返回空的代理。
     */
    private static void installPlatformContext() {
        try {
            Class<?> pa = Class.forName("org.eclipse.core.internal.runtime.PlatformActivator");
            java.lang.reflect.Field cf = pa.getDeclaredField("context");
            cf.setAccessible(true);
            if (cf.get(null) == null) {
                cf.set(null, java.lang.reflect.Proxy.newProxyInstance(
                        MatRuntimeBootstrap.class.getClassLoader(),
                        new Class<?>[]{org.osgi.framework.BundleContext.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "getProperty":
                                    return null;
                                case "getBundles":
                                    return new org.osgi.framework.Bundle[0];
                                case "getBundle":
                                    return ((java.io.File) new java.io.File(".")).getName();
                                default:
                                    Class<?> rt = method.getReturnType();
                                    if (rt == boolean.class) return false;
                                    if (rt == int.class) return 0;
                                    if (rt == long.class) return 0L;
                                    if (rt.isArray()) {
                                        return java.lang.reflect.Array.newInstance(
                                                rt.getComponentType(), 0);
                                    }
                                    return null;
                            }
                        }));
            }
        } catch (Throwable t) {
            if (DEBUG) {
                System.out.println("[boot] platform context not patched: " + t);
            }
        }
    }

    /** Bundle 动态代理：只为兜住 getSymbolicName/getHeaders 等只读调用。 */
    private static org.osgi.framework.Bundle newBundleProxy(final String symbolic) {
        return (org.osgi.framework.Bundle) java.lang.reflect.Proxy.newProxyInstance(
                MatRuntimeBootstrap.class.getClassLoader(),
                new Class<?>[]{org.osgi.framework.Bundle.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getSymbolicName":
                            return symbolic;
                        case "getBundleId":
                            return 42L;
                        case "getState":
                            return org.osgi.framework.Bundle.ACTIVE;
                        case "getVersion":
                            return org.osgi.framework.Version.emptyVersion;
                        case "getHeaders":
                            return new java.util.Hashtable<String, String>();
                        case "getLocation":
                            return "proxy:" + symbolic;
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == long.class) return 0L;
                            return null;
                    }
                });
    }

    /** 从资源 URL 推导 bundle 符号名。两种布局：
     *  eclipse-plugin/&lt;bundle&gt;.xml 直接取文件名；
     *  jar 根 plugin.xml 则取宿主 jar 名的版本前缀。 */
    private static String bundleName(URL url) {
        String s = url.toExternalForm();
        int slash = s.lastIndexOf('/');
        String file = s.substring(slash + 1);
        if (s.contains("eclipse-plugin/") && file.endsWith(".xml")) {
            return file.substring(0, file.length() - 4);
        }
        int bang = s.lastIndexOf("!/");
        String jar = bang >= 0 ? s.substring(0, bang) : s;
        jar = jar.substring(jar.lastIndexOf('/') + 1);
        if (jar.endsWith(".jar")) {
            jar = jar.substring(0, jar.length() - 4);
        }
        int cut = Math.max(jar.lastIndexOf('_'), jar.lastIndexOf('-'));
        return cut > 0 ? jar.substring(0, cut) : jar;
    }

    /** 排序权重：越大越先加载。 */
    private static int rank(URL pluginXml) {
        String s = pluginXml.toString();
        if (s.contains("org.eclipse.mat.api")) return 3;
        if (s.contains("org.eclipse.mat.parser")) return 2;
        if (s.contains("org.eclipse.mat.hprof")) return 1;
        return 0;
    }
}
