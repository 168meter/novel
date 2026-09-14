package com.java2nb.novel.messaging;

import com.java2nb.novel.mapper.ReadingAggregationMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A separate proxy boundary makes every bounded deletion independently durable. */
@Component
public class ReadingDedupCleanupBatch {

    private final ReadingAggregationMapper mapper;

    public ReadingDedupCleanupBatch(ReadingAggregationMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int deleteBefore(LocalDateTime cutoff, int limit) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        if (limit < 1 || limit > 5000) {
            throw new IllegalArgumentException("Cleanup limit must be between 1 and 5000");
        }
        return mapper.deleteDedupBefore(cutoff, limit);
    }
}
