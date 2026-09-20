package com.java2nb.novel.auth.model;

import com.java2nb.novel.core.bean.UserDetails;

public record AuthenticationResult(UserDetails userDetails) { }
