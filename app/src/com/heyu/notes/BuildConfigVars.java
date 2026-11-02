package com.heyu.notes;

/** 构建期常量（由构建脚本生成/覆盖） */
public final class BuildConfigVars {
    public static final String VERSION_NAME = "2.0.13";
    public static final int VERSION_CODE = 18;
    /** 默认的版本检查地址（可被设置里修改） */
    /** 版本清单：走 GitHub Contents API（国内可访问、无 CDN 缓存） */
    public static final String UPDATE_URL = "https://api.github.com/repos/ming-a1/jianjian-notes/contents/version.json";
    /** 备用镜像 */
    public static final String UPDATE_URL_MIRROR = "https://cdn.jsdelivr.net/gh/ming-a1/jianjian-notes@main/version.json";
    private BuildConfigVars() { }
}
