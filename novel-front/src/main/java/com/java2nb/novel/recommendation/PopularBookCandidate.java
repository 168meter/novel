package com.java2nb.novel.recommendation;

import com.java2nb.novel.vo.BookSettingVO;
import java.math.BigInteger;

public class PopularBookCandidate extends BookSettingVO {
    private BigInteger weekSeconds;
    private BigInteger hotSeconds;
    private Long visitCount;

    public BigInteger getWeekSeconds() { return weekSeconds; }
    public void setWeekSeconds(BigInteger value) { weekSeconds = value; }
    public BigInteger getHotSeconds() { return hotSeconds; }
    public void setHotSeconds(BigInteger value) { hotSeconds = value; }
    public Long getVisitCount() { return visitCount; }
    public void setVisitCount(Long value) { visitCount = value; }
}
