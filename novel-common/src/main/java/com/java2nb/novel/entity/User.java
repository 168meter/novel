package com.java2nb.novel.entity;

import io.github.xxyopen.web.valid.AddGroup;
import io.github.xxyopen.web.valid.UpdateGroup;
import jakarta.validation.constraints.*;

import javax.annotation.Generated;
import java.util.Date;
import java.time.LocalDateTime;

public class User {

    @Null(groups = {AddGroup.class, UpdateGroup.class})
    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private Long id;


    @Null(groups = {UpdateGroup.class})
    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private String username;

    @NotBlank(groups = {AddGroup.class}, message = "密码不能为空！")
    @Null(groups = {UpdateGroup.class})
    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private String password;

    @Null(groups = {AddGroup.class, UpdateGroup.class})
    private String email;
    @Null(groups = {AddGroup.class, UpdateGroup.class})
    private String passwordAlgorithm;
    @Null(groups = {AddGroup.class, UpdateGroup.class})
    private Long tokenVersion;
    @Null(groups = {AddGroup.class, UpdateGroup.class})
    private LocalDateTime emailVerifiedAt;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordAlgorithm() { return passwordAlgorithm; }
    public void setPasswordAlgorithm(String passwordAlgorithm) { this.passwordAlgorithm = passwordAlgorithm; }
    public Long getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(Long tokenVersion) { this.tokenVersion = tokenVersion; }
    public LocalDateTime getEmailVerifiedAt() { return emailVerifiedAt; }
    public void setEmailVerifiedAt(LocalDateTime emailVerifiedAt) { this.emailVerifiedAt = emailVerifiedAt; }

    @Null(groups = {AddGroup.class})
    @Pattern(groups = {
        UpdateGroup.class}, regexp = "[\u4E00-\u9FA5A-Za-z0-9_]{1,11}", message = "昵称格式不正确！")
    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private String nickName;

    @Null(groups = {AddGroup.class})
    @Pattern(groups = {
        UpdateGroup.class}, regexp = "^/localPic/\\d{4}/\\d{2}/\\d{2}/[A-Za-z0-9]+\\.(jpg|jpeg|swf|gif|png|JPG|JPEG|SWF|GIF|PNG)$", message = "只能上传图片格式的文件！")
    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private String userPhoto;

    @Null(groups = {AddGroup.class})
    @Min(value = 0, groups = {UpdateGroup.class})
    @Max(value = 1, groups = {UpdateGroup.class})
    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private Byte userSex;

    @Null(groups = {AddGroup.class, UpdateGroup.class})
    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private Long accountBalance;

    @Null(groups = {AddGroup.class, UpdateGroup.class})
    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private Byte status;

    @Null(groups = {AddGroup.class, UpdateGroup.class})
    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private Date createTime;

    @Null(groups = {AddGroup.class, UpdateGroup.class})
    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private Date updateTime;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public Long getId() {
        return id;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setId(Long id) {
        this.id = id;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public String getUsername() {
        return username;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setUsername(String username) {
        this.username = username == null ? null : username.trim();
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public String getPassword() {
        return password;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setPassword(String password) {
        this.password = password;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public String getNickName() {
        return nickName;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setNickName(String nickName) {
        this.nickName = nickName == null ? null : nickName.trim();
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public String getUserPhoto() {
        return userPhoto;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setUserPhoto(String userPhoto) {
        this.userPhoto = userPhoto == null ? null : userPhoto.trim();
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public Byte getUserSex() {
        return userSex;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setUserSex(Byte userSex) {
        this.userSex = userSex;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public Long getAccountBalance() {
        return accountBalance;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setAccountBalance(Long accountBalance) {
        this.accountBalance = accountBalance;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public Byte getStatus() {
        return status;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setStatus(Byte status) {
        this.status = status;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public Date getCreateTime() {
        return createTime;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public Date getUpdateTime() {
        return updateTime;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
}
