package com.java2nb.novel.controller;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.java2nb.common.config.Constant;
import com.java2nb.novel.domain.BookContentDO;
import com.java2nb.novel.domain.BookIndexDO;
import com.java2nb.novel.service.BookContentService;
import com.java2nb.novel.service.BookIndexService;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import io.swagger.annotations.ApiOperation;


import com.java2nb.novel.domain.BookDO;
import com.java2nb.novel.service.BookService;
import com.java2nb.common.utils.PageBean;
import com.java2nb.common.utils.Query;
import com.java2nb.common.utils.R;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 小说表
 *
 * @author xiongxy
 * @email 1179705413@qq.com
 * @date 2020-12-01 03:49:46
 */

@Slf4j
@Controller
@RequestMapping("/novel/book")
public class BookController {

    @Autowired
    private BookService bookService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private BookIndexService bookIndexService;

    @Autowired
    private BookContentService bookContentService;


    private final Pattern PATTERN_BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private final Pattern PATTERN_NBSP = Pattern.compile("&nbsp;", Pattern.CASE_INSENSITIVE);
    private final Pattern PATTERN_ANCHOR_OPEN = Pattern.compile("<a[^>]*>", Pattern.CASE_INSENSITIVE);
    private final Pattern PATTERN_ANCHOR_CLOSE = Pattern.compile("</a>", Pattern.CASE_INSENSITIVE);
    private final Pattern PATTERN_DIV_OPEN = Pattern.compile("<div[^>]*>", Pattern.CASE_INSENSITIVE);
    private final Pattern PATTERN_DIV_CLOSE = Pattern.compile("</div>", Pattern.CASE_INSENSITIVE);
    private final Pattern PATTERN_EMPTY_PARAGRAPH_WITH_LINK = Pattern.compile("<p[^>]*>[^<]*<a[^>]*>[^<]*</a>\\s*</p>", Pattern.CASE_INSENSITIVE);
    private final Pattern PATTERN_P_OPEN = Pattern.compile("<p[^>]*>", Pattern.CASE_INSENSITIVE);
    private final Pattern PATTERN_P_CLOSE = Pattern.compile("</p>", Pattern.CASE_INSENSITIVE);

    @GetMapping()
    @RequiresPermissions("novel:book:book")
    String Book() {
        return "novel/book/book";
    }

    @ApiOperation(value = "获取小说表列表", notes = "获取小说表列表")
    @ResponseBody
    @GetMapping("/list")
    @RequiresPermissions("novel:book:book")
    public R list(@RequestParam Map<String, Object> params) {
        //查询列表数据
        Query query = new Query(params);
        List<BookDO> bookList = bookService.list(query);
        int total = bookService.count(query);
        PageBean pageBean = new PageBean(bookList, total);
        return R.ok().put("data", pageBean);
    }

    @ApiOperation(value = "新增小说表页面", notes = "新增小说表页面")
    @GetMapping("/add")
    @RequiresPermissions("novel:book:add")
    String add() {
        return "novel/book/add";
    }

    @ApiOperation(value = "修改小说表页面", notes = "修改小说表页面")
    @GetMapping("/edit/{id}")
    @RequiresPermissions("novel:book:edit")
    String edit(@PathVariable("id") Long id, Model model) {
        BookDO book = bookService.get(id);
        model.addAttribute("book", book);
        return "novel/book/edit";
    }

    @ApiOperation(value = "查看小说表页面", notes = "查看小说表页面")
    @GetMapping("/detail/{id}")
    @RequiresPermissions("novel:book:detail")
    String detail(@PathVariable("id") Long id, Model model) {
        BookDO book = bookService.get(id);
        model.addAttribute("book", book);
        return "novel/book/detail";
    }

    /**
     * 保存
     */
    @ApiOperation(value = "新增小说表", notes = "新增小说表")
    @ResponseBody
    @PostMapping("/save")
    @RequiresPermissions("novel:book:add")
    public R save(BookDO book) {
        if (bookService.save(book) > 0) {
            return R.ok();
        }
        return R.error();
    }

    /**
     * 修改
     */
    @ApiOperation(value = "修改小说表", notes = "修改小说表")
    @ResponseBody
    @RequestMapping("/update")
    @RequiresPermissions("novel:book:edit")
    public R update(BookDO book) {
        bookService.update(book);
        return R.ok();
    }

    /**
     * 删除
     */
    @ApiOperation(value = "删除小说表", notes = "删除小说表")
    @PostMapping("/remove")
    @ResponseBody
    @RequiresPermissions("novel:book:remove")
    public R remove(Long id) {
        if (bookService.remove(id) > 0) {
            return R.ok();
        }
        return R.error();
    }

    /**
     * 删除
     */
    @ApiOperation(value = "批量删除小说表", notes = "批量删除小说表")
    @PostMapping("/batchRemove")
    @ResponseBody
    @RequiresPermissions("novel:book:batchRemove")
    public R remove(@RequestParam("ids[]") Long[] ids) {
        bookService.batchRemove(ids);
        return R.ok();
    }

    /**
     * 小说下载
     */
    @RequestMapping(value = "/download")
    public void download(@RequestParam("bookId") Long bookId, @RequestParam("bookName") String bookName,
        HttpServletResponse resp) {
        try {
            OutputStream out = resp.getOutputStream();
            Boolean success = redisTemplate
                .opsForValue()
                .setIfAbsent(Constant.BOOK_IS_DOWNLOADING_KEY + bookId, "1", 10, TimeUnit.MINUTES);
            if (Boolean.FALSE.equals(success)) {
                resp.setContentType("text/html;charset=UTF-8");
                out.write("该小说正在下载中，请稍后重试！".getBytes(StandardCharsets.UTF_8));
                out.close();
                return;
            }
            //设置响应头，对文件进行url编码
            bookName = URLEncoder.encode(bookName, StandardCharsets.UTF_8);
            //解决手机端不能下载附件的问题
            resp.setContentType("application/octet-stream");
            resp.setHeader("Content-Disposition", "attachment;filename=" + bookName + ".txt");

            Map<String, Object> params = new HashMap<>();
            params.put("bookId", bookId);
            params.put("sort", "index_num");
            params.put("order", "asc");
            params.put("limit", 9999);
            params.put("offset", 0);
            List<BookIndexDO> bookIndexBigList = bookIndexService.list(params);
            if (!bookIndexBigList.isEmpty()) {
                List<List<BookIndexDO>> bookIndexSmallList = bookIndexBigList.stream().collect(
                    Collectors.groupingBy(item -> bookIndexBigList.indexOf(item) / 100)).values().stream().toList();
                for (List<BookIndexDO> bookIndexList : bookIndexSmallList) {
                    // 获取集合中所有的ID
                    List<Long> bookIndexIds = bookIndexList.stream().map(BookIndexDO::getId).toList();
                    List<BookContentDO> bookContentList = bookContentService.listByIndexIds(bookIndexIds);
                    Map<Long, String> bookContentMap = bookContentList.stream()
                        .collect(Collectors.toMap(BookContentDO::getIndexId, BookContentDO::getContent));
                    for (BookIndexDO bookIndex : bookIndexList) {
                        String indexName = bookIndex.getIndexName();
                        if (indexName != null) {
                            String content = bookContentMap.get(bookIndex.getId());
                            out.write(indexName.getBytes(StandardCharsets.UTF_8));
                            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                            content = PATTERN_BR.matcher(content).replaceAll("\r\n");
                            content = PATTERN_NBSP.matcher(content).replaceAll(" ");
                            content = PATTERN_ANCHOR_OPEN.matcher(content).replaceAll("");
                            content = PATTERN_ANCHOR_CLOSE.matcher(content).replaceAll("");
                            content = PATTERN_DIV_OPEN.matcher(content).replaceAll("");
                            content = PATTERN_DIV_CLOSE.matcher(content).replaceAll("");
                            content = PATTERN_EMPTY_PARAGRAPH_WITH_LINK.matcher(content).replaceAll("");
                            content = PATTERN_P_OPEN.matcher(content).replaceAll("");
                            content = PATTERN_P_CLOSE.matcher(content).replaceAll("\r\n");
                            out.write(content.getBytes(StandardCharsets.UTF_8));
                            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    }
                }

            }

            out.close();


        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            redisTemplate.delete(Constant.BOOK_IS_DOWNLOADING_KEY + bookId);
        }

    }

}
