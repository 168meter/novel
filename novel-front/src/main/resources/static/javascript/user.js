var UserUtil = {
    msgStyle: 'background-color:#333; color:#fff; text-align:center; border:none; font-size:20px; padding:10px;',
    GetFavoritesNew: function () {
        var bIdList = "";
        $(".book_list").each(function () {
            bIdList += "," + $(this).attr("vals");
        });
        if (bIdList != "") {
        }
    },
    GetHistory: function () {
        var bIdList = "";
        $(".book_list").each(function () {
            bIdList += "," + $(this).attr("vals");
        });
        if (bIdList != "") {
        }
    },
    GetChapterInfo: function () {
        var cIdList = "";
        $(".showCName").each(function () {
            cIdList += "," + $(this).attr("vals");
        });
        if (cIdList != "") {
        }
    },
    SignDay: function () {
        if (!signed) {
            signed = true;
        }
    },
    SignDayStatus: function () {
    }
};

var AuthPage = (function ($) {
    "use strict";

    var CODE_WAIT_SECONDS = 60;

    function value(selector) {
        return $(selector).val() || "";
    }

    function message(selector, text) {
        $(selector).text(text || "操作失败，请稍后重试");
    }

    function clearSecrets(selectors) {
        $.each(selectors, function (_, selector) {
            $(selector).val("");
        });
    }

    function validEmail(email) {
        return email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
    }

    function validPassword(password) {
        return password.length >= 8 && password.length <= 64;
    }

    function responseMessage(response) {
        return response && response.msg ? response.msg : "操作失败，请稍后重试";
    }

    function startCodeCountdown(button) {
        var remaining = CODE_WAIT_SECONDS;
        button.prop("disabled", true);
        var timer = window.setInterval(function () {
            remaining -= 1;
            if (remaining <= 0) {
                window.clearInterval(timer);
                button.val("重新发送").prop("disabled", false);
                return;
            }
            button.val("重新发送(" + remaining + ")");
        }, 1000);
        button.val("重新发送(" + remaining + ")");
    }

    function sendEmailCode(options) {
        var email = $.trim(value(options.email));
        var button = $(options.button);
        if (!validEmail(email)) {
            message(options.error, "请输入正确的邮箱地址");
            return;
        }
        button.prop("disabled", true);
        $.ajax({
            type: "POST",
            url: options.url,
            data: {email: email},
            dataType: "json"
        }).done(function (response) {
            if (response.code === 200) {
                message(options.error, "验证码已发送，请检查邮箱");
                startCodeCountdown(button);
            } else {
                message(options.error, responseMessage(response));
                button.prop("disabled", false);
            }
        }).fail(function (xhr) {
            message(options.error, responseMessage(xhr.responseJSON));
            button.prop("disabled", false);
        });
    }

    function showLoginCaptcha() {
        $("#imageCaptchaGroup").show();
        $("#imageCaptchaImage").attr("src", "/file/getVerify?nonce=" + new Date().getTime());
    }

    function loginFailure(response) {
        if (response && response.data && response.data.captchaRequired === true) {
            showLoginCaptcha();
        }
        message("#loginError", responseMessage(response));
    }

    function safeOriginUrl() {
        var origin = getSearchString("originUrl");
        return origin && origin.charAt(0) === "/" && origin.charAt(1) !== "/" ? origin : "/";
    }

    function initLogin() {
        if (localStorage.getItem("autoLogin") === "1") {
            $("#autoLogin").prop("checked", true);
        }
        $("#imageCaptchaImage").on("click", showLoginCaptcha);
        $("#btnLogin").on("click", function () {
            var button = $(this);
            var account = $.trim(value("#loginAccount"));
            var password = value("#loginPassword");
            if (!account || account.length > 254) {
                message("#loginError", "请输入邮箱或历史手机号");
                return;
            }
            if (!password || password.length > 64) {
                message("#loginError", "请输入密码");
                return;
            }
            button.prop("disabled", true);
            $.ajax({
                type: "POST",
                url: "/user/login",
                data: {
                    loginAccount: account,
                    password: password,
                    imageCaptcha: value("#imageCaptcha")
                },
                dataType: "json"
            }).done(function (response) {
                if (response.code === 200) {
                    var cookieOptions = {path: "/"};
                    if ($("#autoLogin").is(":checked")) {
                        cookieOptions.expires = 7;
                        localStorage.setItem("autoLogin", "1");
                    } else {
                        localStorage.setItem("autoLogin", "0");
                    }
                    $.cookie("Authorization", response.data.token, cookieOptions);
                    window.location.href = safeOriginUrl();
                } else {
                    loginFailure(response);
                }
            }).fail(function (xhr) {
                loginFailure(xhr.responseJSON);
            }).always(function () {
                clearSecrets(["#loginPassword", "#imageCaptcha"]);
                button.prop("disabled", false);
            });
        });
    }

    function initRegister() {
        $("#registerSendCode").on("click", function () {
            sendEmailCode({
                email: "#registerEmail",
                button: "#registerSendCode",
                error: "#registerError",
                url: "/user/register/email-code"
            });
        });
        $("#btnRegister").on("click", function () {
            var button = $(this);
            var email = $.trim(value("#registerEmail"));
            var code = value("#registerCode");
            var password = value("#registerPassword");
            var confirmation = value("#registerConfirmPassword");
            if (!validEmail(email) || !/^\d{6}$/.test(code) || !validPassword(password)) {
                message("#registerError", "请填写有效邮箱、6位验证码和8-64字符密码");
                return;
            }
            if (password !== confirmation) {
                message("#registerError", "两次输入的密码不一致");
                return;
            }
            button.prop("disabled", true);
            $.ajax({
                type: "POST",
                url: "/user/register",
                data: {email: email, code: code, password: password, confirmPassword: confirmation},
                dataType: "json"
            }).done(function (response) {
                if (response.code === 200) {
                    $.cookie("Authorization", response.data.token, {path: "/"});
                    window.location.href = "/";
                } else {
                    message("#registerError", responseMessage(response));
                }
            }).fail(function (xhr) {
                message("#registerError", responseMessage(xhr.responseJSON));
            }).always(function () {
                clearSecrets(["#registerCode", "#registerPassword", "#registerConfirmPassword"]);
                button.prop("disabled", false);
            });
        });
    }

    function initPasswordReset() {
        $("#resetSendCode").on("click", function () {
            sendEmailCode({
                email: "#resetEmail",
                button: "#resetSendCode",
                error: "#resetError",
                url: "/user/password-reset/email-code"
            });
        });
        $("#btnResetPassword").on("click", function () {
            var button = $(this);
            var email = $.trim(value("#resetEmail"));
            var code = value("#resetCode");
            var password = value("#resetPassword");
            var confirmation = value("#resetConfirmPassword");
            if (!validEmail(email) || !/^\d{6}$/.test(code) || !validPassword(password)) {
                message("#resetError", "请填写有效邮箱、6位验证码和8-64字符密码");
                return;
            }
            if (password !== confirmation) {
                message("#resetError", "两次输入的密码不一致");
                return;
            }
            button.prop("disabled", true);
            $.ajax({
                type: "POST",
                url: "/user/password-reset",
                data: {email: email, code: code, password: password, confirmPassword: confirmation},
                dataType: "json"
            }).done(function (response) {
                if (response.code === 200) {
                    window.location.href = "/user/login.html";
                } else {
                    message("#resetError", responseMessage(response));
                }
            }).fail(function (xhr) {
                message("#resetError", responseMessage(xhr.responseJSON));
            }).always(function () {
                clearSecrets(["#resetCode", "#resetPassword", "#resetConfirmPassword"]);
                button.prop("disabled", false);
            });
        });
    }

    function initPasswordChange() {
        $("#btnExchangePassword").removeAttr("onclick").on("click", function () {
            var button = $(this);
            var oldPassword = value("#txtOldPass");
            var password = value("#txtNewPass1");
            var confirmation = value("#txtNewPass2");
            if (!oldPassword || !validPassword(password) || password !== confirmation) {
                message("#LabErr", "请检查原密码和两次输入的新密码");
                return;
            }
            button.prop("disabled", true);
            $.ajax({
                type: "POST",
                url: "/user/updatePassword",
                data: {oldPassword: oldPassword, newPassword1: password, newPassword2: confirmation},
                dataType: "json"
            }).done(function (response) {
                if (response.code === 200) {
                    $.cookie("Authorization", response.data.token, {path: "/"});
                    window.location.href = "/user/setup.html";
                } else if (response.code === 1001) {
                    window.location.href = "/user/login.html";
                } else {
                    message("#LabErr", responseMessage(response));
                }
            }).fail(function (xhr) {
                message("#LabErr", responseMessage(xhr.responseJSON));
            }).always(function () {
                clearSecrets(["#txtOldPass", "#txtNewPass1", "#txtNewPass2"]);
                button.prop("disabled", false);
            });
        });
    }

    return {
        initLogin: initLogin,
        initRegister: initRegister,
        initPasswordReset: initPasswordReset,
        initPasswordChange: initPasswordChange
    };
})(jQuery);
