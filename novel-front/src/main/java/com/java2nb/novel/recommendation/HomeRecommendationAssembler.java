package com.java2nb.novel.recommendation;

import com.java2nb.novel.vo.BookSettingVO;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Pure assembly: eligibility is enforced by the mapper; no database/configuration writes. */
public class HomeRecommendationAssembler {
    private static final int[] CAPS = {4,10,5,6,6};

    public Map<String,List<BookSettingVO>> compose(List<PopularBookCandidate> candidates,
        List<BookSettingVO> configured) {
        var home = configuredOnly(configured);
        var week = new ArrayList<BookSettingVO>();
        append(week, ranked(candidates,PopularBookCandidate::getWeekSeconds),5,row -> true);
        append(week,home.get("2"),5,row -> true);

        Set<Long> weekIds = new HashSet<>();
        week.forEach(row -> weekIds.add(row.getBookId()));
        var hotCandidates = ranked(candidates,PopularBookCandidate::getHotSeconds);
        var hot = new ArrayList<BookSettingVO>();
        Predicate<BookSettingVO> independent = row -> !weekIds.contains(row.getBookId());
        // Prefer independent algorithm picks, then independent configured picks.
        // Only a shortage permits overlap with the final weekly group.
        append(hot,hotCandidates,6,independent);
        append(hot,home.get("3"),6,independent);
        append(hot,hotCandidates,6,row -> weekIds.contains(row.getBookId()));
        append(hot,home.get("3"),6,row -> weekIds.contains(row.getBookId()));

        assignSlots(week,(byte)2);
        assignSlots(hot,(byte)3);
        home.put("2",week);
        home.put("3",hot);
        return home;
    }

    public Map<String,List<BookSettingVO>> configuredOnly(List<BookSettingVO> configured) {
        var home = new LinkedHashMap<String,List<BookSettingVO>>();
        for (int type = 0; type <= 4; type++) home.put(Integer.toString(type),new ArrayList<>());
        for (BookSettingVO row : configured) {
            if (row == null || row.getBookId() == null || row.getType() == null) continue;
            int type = row.getType();
            if (type < 0 || type > 4) continue;
            append(home.get(Integer.toString(type)),List.of(row),CAPS[type],pick -> true);
        }
        return home;
    }

    private List<PopularBookCandidate> ranked(List<PopularBookCandidate> candidates,
        Function<PopularBookCandidate,BigInteger> seconds) {
        Comparator<PopularBookCandidate> order = Comparator.comparing(seconds).reversed()
            .thenComparing(Comparator.comparingLong((PopularBookCandidate row) ->
                row.getVisitCount() == null ? 0 : row.getVisitCount()).reversed())
            .thenComparing(PopularBookCandidate::getBookId);
        return candidates.stream().filter(row -> row != null && row.getBookId() != null)
            .filter(row -> seconds.apply(row) != null && seconds.apply(row).signum() > 0)
            .sorted(order).toList();
    }

    private void append(List<BookSettingVO> target,List<? extends BookSettingVO> source,int cap,
        Predicate<BookSettingVO> include) {
        Set<Long> ids = new HashSet<>();
        target.forEach(row -> ids.add(row.getBookId()));
        for (BookSettingVO row : source) {
            if (target.size() >= cap) break;
            if (include.test(row) && ids.add(row.getBookId())) target.add(copy(row));
        }
    }

    private void assignSlots(List<BookSettingVO> group,byte type) {
        for (int slot = 0; slot < group.size(); slot++) {
            group.get(slot).setType(type);
            group.get(slot).setSort((byte)(slot+1));
        }
    }

    private BookSettingVO copy(BookSettingVO source) {
        var result = new BookSettingVO();
        result.setId(source.getId()); result.setBookId(source.getBookId());
        result.setType(source.getType()); result.setSort(source.getSort());
        result.setBookName(source.getBookName()); result.setPicUrl(source.getPicUrl());
        result.setAuthorName(source.getAuthorName()); result.setBookDesc(source.getBookDesc());
        result.setScore(source.getScore()); result.setCatId(source.getCatId());
        result.setCatName(source.getCatName()); result.setBookStatus(source.getBookStatus());
        result.setCreateTime(copyDate(source.getCreateTime())); result.setUpdateTime(copyDate(source.getUpdateTime()));
        result.setCreateUserId(source.getCreateUserId()); result.setUpdateUserId(source.getUpdateUserId());
        return result;
    }

    private Date copyDate(Date source) { return source == null ? null : new Date(source.getTime()); }
}
