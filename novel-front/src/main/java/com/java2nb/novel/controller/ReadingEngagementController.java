package com.java2nb.novel.controller;

import com.java2nb.novel.core.utils.ThreadLocalUtil;
import com.java2nb.novel.engagement.ReadingClientAddressResolver;
import com.java2nb.novel.engagement.ReadingEngagementService;
import com.java2nb.novel.engagement.ReadingHeartbeatRequest;
import com.java2nb.novel.engagement.ReadingHeartbeatOutcome;
import com.java2nb.novel.engagement.ReadingHeartbeatResponse;
import io.github.xxyopen.model.resp.RestResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

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

    @PostMapping(value = "/heartbeat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RestResult<ReadingHeartbeatResponse>> heartbeat(
        @Valid @RequestBody ReadingHeartbeatRequest request,
        HttpServletRequest servletRequest
    ) {
        ReadingHeartbeatOutcome outcome = engagementService.handle(
            request,
            ThreadLocalUtil.getClientId(),
            clientAddressResolver.resolve(servletRequest));
        return ResponseEntity.status(httpStatus(outcome))
            .contentType(MediaType.APPLICATION_JSON)
            .body(RestResult.ok(new ReadingHeartbeatResponse(publicOutcome(outcome))));
    }

    private HttpStatus httpStatus(ReadingHeartbeatOutcome outcome) {
        return switch (outcome) {
            case ACCEPTED, DUPLICATE, TOO_EARLY, DAILY_CAP_REACHED -> HttpStatus.OK;
            case SESSION_RATE_LIMITED, IP_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case INVALID_PAGE -> HttpStatus.CONFLICT;
            case REDIS_ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private ReadingHeartbeatResponse.Outcome publicOutcome(ReadingHeartbeatOutcome outcome) {
        return switch (outcome) {
            case ACCEPTED -> ReadingHeartbeatResponse.Outcome.ACCEPTED;
            case DUPLICATE -> ReadingHeartbeatResponse.Outcome.DUPLICATE;
            case TOO_EARLY -> ReadingHeartbeatResponse.Outcome.TOO_EARLY;
            case DAILY_CAP_REACHED -> ReadingHeartbeatResponse.Outcome.DAILY_CAP;
            case SESSION_RATE_LIMITED, IP_RATE_LIMITED -> ReadingHeartbeatResponse.Outcome.RATE_LIMIT;
            case INVALID_PAGE -> ReadingHeartbeatResponse.Outcome.INVALID_PAGE;
            case REDIS_ERROR -> ReadingHeartbeatResponse.Outcome.REDIS_ERROR;
        };
    }
}
