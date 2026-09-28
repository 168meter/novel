package com.java2nb.novel.service;

import java.util.List;
import java.util.Map;

/**
 * @author xiongxiaoyang
 * @date 2025/10/24
 */
public interface EmailService {

    void sendHtmlEmail(String subject, String templateName, Map<String, Object> templateModel, List<String> to);

}
