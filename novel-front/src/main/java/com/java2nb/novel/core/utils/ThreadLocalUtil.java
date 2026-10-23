package com.java2nb.novel.core.utils;

import com.java2nb.novel.core.cache.CacheKey;
import com.java2nb.novel.core.cache.CacheService;

/**
 * 模板操作工具类
 * @author Administrator
 */
public class ThreadLocalUtil {

    /**
     * 存储当前线程访问的模板目录
     * */
    private static final ThreadLocal<String> templateDir = new ThreadLocal<>();

    /** Whether the current request explicitly selected its template directory. */
    private static final ThreadLocal<Boolean> templateDirOverride = new ThreadLocal<>();

    /**
     * 存储当前会话的sessionID
     * */
    private static final ThreadLocal<String> clientId = new ThreadLocal<>();

    /**
     * 设置当前应该访问的模板目录
     * */
    public static void setTemplateDir(String dir){
        templateDir.set(dir);
    }

    /** Sets a template directory that must take effect for the current request. */
    public static void setTemplateDirOverride(String dir) {
        templateDir.set(dir);
        templateDirOverride.set(Boolean.TRUE);
    }

    /**
     * 获取当前应该访问的模板路径前缀
     * */
    public static String getTemplateDir(){
        if (Boolean.TRUE.equals(templateDirOverride.get())) {
            return templateDir.get();
        }
        try {
            CacheService cacheService = SpringUtil.getBean(CacheService.class);
            String prefix = cacheService.get(CacheKey.TEMPLATE_DIR_KEY+clientId.get());
            if(prefix != null){
                return prefix;
            }
        } catch (RuntimeException ignored) {
            // Fall back to the template selected by NovelFilter for this request.
        }
        return templateDir.get();
    }
    
    /**
     * 设置当前访问线程的客户端ID
     * */
    public static void setClientId(String id){
        clientId.set(id);
    }

    /**
     * 获取当前访问线程的客户端ID
     * */
    public static String getClientId(){
        return clientId.get();
    }

    /** Clears every request-scoped value owned by this utility. */
    public static void clear() {
        templateDir.remove();
        templateDirOverride.remove();
        clientId.remove();
    }

}
