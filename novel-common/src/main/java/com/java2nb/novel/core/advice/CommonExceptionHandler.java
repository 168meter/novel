package com.java2nb.novel.core.advice;

import io.github.xxyopen.model.resp.RestResult;
import io.github.xxyopen.model.resp.SysResultCode;
import io.github.xxyopen.web.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 统一异常处理器
 *
 * @author xiongxiaoyang
 */
@Slf4j
@RestControllerAdvice
public class CommonExceptionHandler {

    public CommonExceptionHandler() {
    }

    @ExceptionHandler({BindException.class})
    public ResponseEntity<RestResult<Void>> handlerBindException(BindException e) {
        // Binding exceptions include rejected values, which can be passwords or one-time codes.
        log.warn("Request validation failed");
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
            .body(RestResult.fail(SysResultCode.PARAM_ERROR));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
        ConstraintViolationException.class, ServletRequestBindingException.class})
    public Object handleClientInputException(HttpServletRequest request, Exception e) {
        log.warn("Request input could not be parsed");
        if (isJsonRequest(request)) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(RestResult.fail(SysResultCode.PARAM_ERROR));
        }
        return new ModelAndView("404", HttpStatus.BAD_REQUEST).addObject("errorStatus", 400);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Object handleMissingResource(HttpServletRequest request, NoResourceFoundException e) {
        if (isJsonRequest(request)) {
            RestResult<Void> result = RestResult.error();
            result.setCode(404);
            result.setMsg("请求的内容不存在");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(result);
        }
        return new ModelAndView("404", HttpStatus.NOT_FOUND).addObject("errorStatus", 404);
    }

    @ExceptionHandler({BusinessException.class})
    public RestResult<Void> handlerBusinessException(BusinessException e) {
        log.error(e.getMessage(), e);
        return RestResult.fail(e.getResultCode());
    }

    @ExceptionHandler(Exception.class)
    public Object handleException(HttpServletRequest request, Exception e) {
        log.error(e.getMessage(), e);
        if (isJsonRequest(request)) {
            // 如果是REST请求，返回JSON格式的错误响应
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                .body(RestResult.error());
        } else {
            // Reuse the existing safe error template while preserving the real server status.
            return new ModelAndView("404", HttpStatus.INTERNAL_SERVER_ERROR).addObject("errorStatus", 500);
        }
    }

    private boolean isJsonRequest(HttpServletRequest request) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler instanceof HandlerMethod handlerMethod) {
            if (AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), RestController.class)
                || handlerMethod.hasMethodAnnotation(ResponseBody.class)) {
                return true;
            }
        }
        String acceptHeader = request.getHeader("Accept");
        if (acceptHeader == null) {
            return false;
        }
        try {
            List<MediaType> acceptedTypes = MediaType.parseMediaTypes(acceptHeader);
            MediaType.sortBySpecificityAndQuality(acceptedTypes);
            for (MediaType acceptedType : acceptedTypes) {
                if (acceptedType.getQualityValue() == 0.0d) {
                    continue;
                }
                if (acceptedType.isCompatibleWith(MediaType.TEXT_HTML)) {
                    return false;
                }
                if (acceptedType.isCompatibleWith(MediaType.APPLICATION_JSON)
                    || acceptedType.getSubtype().endsWith("+json")) {
                    return true;
                }
            }
        } catch (InvalidMediaTypeException ignored) {
            // Malformed negotiation headers do not change a page request into a JSON API request.
        }
        return false;
    }

}
