# ==========================================================================
# release 为什么关掉 minify / shrink（app/build.gradle.kts 的 release{ }，T1.7）
# ==========================================================================
# 现状：isMinifyEnabled = false，isShrinkResources = false。原因不是"没空配"，
# 而是这套构建的稳定性依赖类名 / 字段名 / 资源名在运行期原样可见：
#
# 1. 插件宿主走反射装载。plugin-html 等插件的类与资源在运行期按字符串名查找，
#    R8 一改名（类、方法、字段、内部类后缀）就在真机上报 ClassNotFoundException /
#    NoSuchMethodException，且只在装机后才暴露。
# 2. JS 宿主与引擎跨进程接口按名字绑定。render.js 通过 Class.getResourceAsStream
#    取 Java 资源，:node 独立进程经 AIDL 调用，名字错一位就是运行期崩溃。
# 3. 资源收缩会删掉"代码里没有静态引用"的插件资源（JS / 模板 / 图标）。
# 4. 开混淆前必须先补全 keep 规则，并跑一遍全量插件回归（安装、启动、每个插件
#    的加载与渲染）；只跑 :app:assembleRelease 通过不能算数。
#
# 因此这里暂时留空，不做任何 keep 规则：开关关着的时候 keep 规则不产生影响，
# 写了反而会让人误以为混淆已经验证过。要开混淆，先按上面三步做完再往这里加规则。
