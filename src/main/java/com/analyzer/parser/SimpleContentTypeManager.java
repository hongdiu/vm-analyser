package com.analyzer.parser;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.core.runtime.content.IContentTypeMatcher;
import org.eclipse.core.runtime.content.IContentTypeSettings;
import org.eclipse.core.runtime.preferences.IScopeContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小可用的 IContentTypeManager（plain 模式替身）。
 *
 * <p>Equinox 正版 ContentTypeManager 建目录树时会触发 OSGi ServiceCaller
 * （非框架环境必炸），而 MAT 只用内容类型来“识别 hprof / MAT 索引文件格式”。
 * 这里按文件名扩展 + 文件头魔数实现同样的判定，接口行为完全够用。</p>
 *
 * <p>注册进 InternalPlatform 的方式见 {@link MatRuntimeBootstrap}。</p>
 */
final class SimpleContentTypeManager implements IContentTypeManager {

    /** MAT 索引文件内容类型 id（与 org.eclipse.mat.parser plugin.xml 声明一致）。 */
    static final String ID_INDEX = "org.eclipse.mat.Index";
    /** HPROF 转储内容类型 id（与 org.eclipse.mat.hprof plugin.xml 声明一致）。 */
    static final String ID_HPROF = "org.eclipse.mat.HprofHeapDump";
    /** 二者共同的基类型 id。 */
    static final String ID_HEAPDUMP = "org.eclipse.mat.JavaHeapDump";

    private final Map<String, SimpleContentType> types = new HashMap<>();

    SimpleContentTypeManager() {
        SimpleContentType base = new SimpleContentType(ID_HEAPDUMP, "Java Heap Dump", null,
                new String[0], null, 0);
        types.put(base.getId(), base);
        types.put(ID_INDEX, new SimpleContentType(ID_INDEX, "MAT Index", base,
                new String[]{"index"},
                "MAT_01".getBytes(StandardCharsets.US_ASCII), 8));
        types.put(ID_HPROF, new SimpleContentType(ID_HPROF, "HPROF Heap Dump", base,
                new String[]{"hprof", "bin", "dmp", "gz"},
                "JAVA PROFILE".getBytes(StandardCharsets.US_ASCII), 0));
    }

    // ================= IContentTypeManager =================

    @Override
    public IContentType getContentType(String id) {
        return types.get(id);
    }

    @Override
    public IContentType[] getAllContentTypes() {
        return types.values().toArray(new IContentType[0]);
    }

    @Override
    public IContentType addContentType(String id, String name, IContentType base) throws CoreException {
        SimpleContentType baseType = base instanceof SimpleContentType ? (SimpleContentType) base : null;
        SimpleContentType t = new SimpleContentType(id, name, baseType, new String[0], null, 0);
        types.put(id, t);
        return t;
    }

    @Override
    public void removeContentType(String id) throws CoreException {
        types.remove(id);
    }

    @Override
    public void addContentTypeChangeListener(IContentTypeChangeListener listener) {
    }

    @Override
    public void removeContentTypeChangeListener(IContentTypeChangeListener listener) {
    }

    @Override
    public IContentTypeMatcher getMatcher(ISelectionPolicy policy, IScopeContext context) {
        return this;
    }

    /** 兼容多上下文重载（接口 default 方法之外的显式实现）。 */
    public IContentTypeMatcher getMatcher(ISelectionPolicy policy, IScopeContext... contexts) {
        return this;
    }

    // ================= IContentTypeMatcher =================

    @Override
    public IContentType[] findContentTypesFor(String fileName) {
        if (fileName == null) {
            return new IContentType[0];
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".hprof.gz")) {
            lower = lower.substring(0, lower.length() - 3); // 剥掉 .gz 再看
        }
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        List<IContentType> out = new ArrayList<>();
        for (SimpleContentType t : types.values()) {
            if (t.matchesExtension(ext)) {
                out.add(t);
            }
        }
        return out.toArray(new IContentType[0]);
    }

    @Override
    public IContentType[] findContentTypesFor(InputStream contents, String fileName) {
        IContentType[] byName = findContentTypesFor(fileName);
        if (contents == null || byName.length == 0) {
            return byName;
        }
        // 读前 64 字节做魔数复核（读不了就退回文件名判定）
        byte[] head = new byte[64];
        int read = 0;
        try {
            if (contents.markSupported()) {
                contents.mark(head.length);
                read = contents.read(head);
                contents.reset();
            }
        } catch (IOException ignore) {
        }
        if (read <= 0) {
            return byName;
        }
        List<IContentType> matched = new ArrayList<>();
        for (IContentType t : byName) {
            if (matchesMagicOf(t, head)) {
                matched.add(t);
            }
        }
        return matched.toArray(new IContentType[0]);
    }

    @Override
    public IContentType findContentTypeFor(String fileName) {
        IContentType[] all = findContentTypesFor(fileName);
        return all.length == 0 ? null : all[0];
    }

    @Override
    public IContentType findContentTypeFor(InputStream contents, String fileName) {
        IContentType[] all = findContentTypesFor(contents, fileName);
        return all.length == 0 ? null : all[0];
    }

    @Override
    public IContentDescription getDescriptionFor(InputStream contents, String fileName,
                                                 QualifiedName[] options) {
        IContentType t = findContentTypeFor(contents, fileName);
        return t == null ? null : new SimpleDescription(t, options);
    }

    @Override
    public IContentDescription getDescriptionFor(Reader contents, String fileName,
                                                 QualifiedName[] options) {
        IContentType t = findContentTypeFor(fileName);
        return t == null ? null : new SimpleDescription(t, options);
    }

    /** 魔数匹配辅助（接口方法之外的内部调用）。 */
    private static boolean matchesMagicOf(IContentType t, byte[] head) {
        return t instanceof SimpleContentType && ((SimpleContentType) t).matchesMagic(head);
    }

    /** 简单内容类型实现：id + 基类型链 + 扩展名 + 魔数。 */
    private final class SimpleContentType implements IContentType {
        private final String id;
        private final String name;
        private final SimpleContentType base;
        private final List<String> extensions;
        private final byte[] magic;
        private final int magicOffset;

        SimpleContentType(String id, String name, SimpleContentType base,
                          String[] extensions, byte[] magic, int magicOffset) {
            this.id = id;
            this.name = name;
            this.base = base;
            this.extensions = new ArrayList<>();
            for (String e : extensions) {
                this.extensions.add(e);
            }
            this.magic = magic;
            this.magicOffset = magicOffset;
        }

        boolean matchesExtension(String ext) {
            return extensions.contains(ext);
        }

        boolean matchesMagic(byte[] head) {
            if (magic == null) {
                return true;
            }
            if (head.length < magicOffset + magic.length) {
                return false;
            }
            for (int i = 0; i < magic.length; i++) {
                if (head[magicOffset + i] != magic[i]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public IContentType getBaseType() {
            return base;
        }

        @Override
        public boolean isKindOf(IContentType another) {
            for (SimpleContentType t = this; t != null; t = t.base) {
                if (another != null && t.id.equals(another.getId())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String[] getFileSpecs(int type) {
            return type == FILE_EXTENSION_SPEC ? extensions.toArray(new String[0]) : new String[0];
        }

        @Override
        public void addFileSpec(String fileSpec, int type) {
            if (type == FILE_EXTENSION_SPEC) {
                extensions.add(fileSpec);
            }
        }

        @Override
        public void removeFileSpec(String fileSpec, int type) {
            if (type == FILE_EXTENSION_SPEC) {
                extensions.remove(fileSpec);
            }
        }

        @Override
        public String getDefaultCharset() {
            return null;
        }

        @Override
        public void setDefaultCharset(String charset) {
            // 不支持持久化，忽略
        }

        @Override
        public boolean isUserDefined() {
            return false;
        }

        @Override
        public IContentDescription getDescriptionFor(InputStream stream, QualifiedName[] options) {
            return new SimpleDescription(this, options);
        }

        @Override
        public IContentDescription getDescriptionFor(Reader reader, QualifiedName[] options) {
            return new SimpleDescription(this, options);
        }

        @Override
        public IContentDescription getDefaultDescription() {
            return new SimpleDescription(this, null);
        }

        @Override
        public IContentTypeSettings getSettings(IScopeContext context) {
            return this;
        }

        @Override
        public boolean isAssociatedWith(String pluginId) {
            return true;
        }

        @Override
        public boolean isAssociatedWith(String pluginId, IScopeContext context) {
            return true;
        }
    }

    /** 空内容描述：MAT 只用它确认类型，不依赖具体属性值。 */
    private static final class SimpleDescription implements IContentDescription {
        private final IContentType type;
        private final QualifiedName[] requested;
        private final Map<QualifiedName, Object> props = new HashMap<>();

        SimpleDescription(IContentType type, QualifiedName[] requested) {
            this.type = type;
            this.requested = requested;
        }

        @Override
        public boolean isRequested(QualifiedName property) {
            if (requested == null) {
                return true;
            }
            for (QualifiedName q : requested) {
                if (q.equals(property)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String getCharset() {
            return null;
        }

        @Override
        public IContentType getContentType() {
            return type;
        }

        @Override
        public Object getProperty(QualifiedName property) {
            return props.get(property);
        }

        @Override
        public void setProperty(QualifiedName property, Object value) {
            props.put(property, value);
        }
    }
}
