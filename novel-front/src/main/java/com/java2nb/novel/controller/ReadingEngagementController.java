package com.java2nb.novel.controller;

import com.java2nb.novel.core.utils.ThreadLocalUtil;
import com.java2nb.novel.engagement.ReadingClientAddressResolver;
import com.java2nb.novel.engagement.ReadingEngagementService;
import com.java2nb.novel.engagement.ReadingHeartbeatRequest;
import io.github.xxyopen.model.resp.RestResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/engagement/reading")
public class ReadingEngagementController {

    private final ReadingEngagementService engagementService;
    private final ReadingClientAddressResolver clientAddressResolver;

    public ReadingEngagementController(
        ReadingEngagementService engagementService,
        ReadingClientAddressResolver clientAddressResolver
    ) {
        this.engagementService = engagementService;
        this.clientAddressResolver = clientAddressResolver;
    }

    @PostMapping("/heartbeat")
    public RestResult<Void> heartbeat(
        @Valid @RequestBody ReadingHeartbeatRequest request,
        HttpServletRequest servletRequest
    ) {
        engagementService.handle(
            request,
            ThreadLocalUtil.getClientId(),
            clientAddressResolver.resolve(servletRequest));
        return RestResult.ok();
    }
}
