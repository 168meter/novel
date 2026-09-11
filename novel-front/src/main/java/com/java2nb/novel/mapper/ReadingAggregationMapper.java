package com.java2nb.novel.mapper;

import com.java2nb.novel.engagement.ReadingDailyAggregate;
import com.java2nb.novel.engagement.ReadingDedupRecord;
import com.java2nb.novel.engagement.ReadingDedupState;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ReadingAggregationMapper {

    int insertDedupRecords(@Param("records") List<ReadingDedupRecord> records);

    List<ReadingDedupState> findDedupStates(@Param("eventIds") List<String> eventIds);

    int upsertDailyAggregates(@Param("aggregates") List<ReadingDailyAggregate> aggregates);

    int deleteDedupBefore(
        @Param("cutoff") LocalDateTime cutoff,
        @Param("limit") int limit
    );
}
