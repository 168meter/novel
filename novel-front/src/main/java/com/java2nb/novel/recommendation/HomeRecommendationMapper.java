package com.java2nb.novel.recommendation;

import com.java2nb.novel.vo.BookSettingVO;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HomeRecommendationMapper {
    /**
     * Bounded union of weekly top 5 and fifteen-day top 11, not a display-order list.
     * Consumers must sort separately by the corresponding seconds, visits, then book ID.
     */
    List<PopularBookCandidate> listCandidates(@Param("weekStart") LocalDate weekStart,
        @Param("hotStart") LocalDate hotStart, @Param("endExclusive") LocalDate endExclusive);

    List<BookSettingVO> listConfigured();
}
