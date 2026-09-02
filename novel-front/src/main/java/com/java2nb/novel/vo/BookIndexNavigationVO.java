package com.java2nb.novel.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 相邻章节导航。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookIndexNavigationVO {

    private Long preBookIndexId;

    private Long nextBookIndexId;
}
