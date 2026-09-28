package com.java2nb.novel.service.impl;

import com.java2nb.novel.core.utils.IpUtil;
import com.java2nb.novel.service.IpLocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lionsoul.ip2region.xdb.Searcher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * IpLocationService 实现类
 *
 * @author xiongxiaoyang
 * @date 2025/6/30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpLocationServiceImpl implements IpLocationService {

    private final Searcher searcher;

    @Override
    public String getLocation(String ip) {
        try {
            // 示例返回："中国|0|湖北省|武汉市|电信"
            String region = searcher.search(ip);
            log.info("IP：{}，区域：{}", ip, region);
            String[] regions = region.split("\\|");
            if (regions.length > 0) {
                // 国家
                String country = regions[0];
                if ("0".equals(country)) {
                    // 内网IP，直接获取本机公网IP
                    String publicIp = IpUtil.getPublicIP();
                    log.info("内网IP：{}，本机公网IP：{}", ip, publicIp);
                    if (StringUtils.hasText(publicIp)) {
                        return getLocation(publicIp);
                    }
                } else if ("中国".equals(country)) {
                    // 若为中国，则尝试返回省份（regions[2]）
                    if (regions.length <= 2) {
                        // 数据不足，直接返回国家
                        return country;
                    }
                    String province = regions[2];
                    if (!StringUtils.hasText(province) || "0".equals(province)) {
                        // 省份字段为空或未知，返回国家
                        return country;
                    }
                    // 去掉末尾的“省”字
                    return province.endsWith("省") ? province.substring(0, province.length() - 1) : province;
                } else {
                    // 非中国，返回国家名
                    return country;
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return "未知地区";
    }

}
