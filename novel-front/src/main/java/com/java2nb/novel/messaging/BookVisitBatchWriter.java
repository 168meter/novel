package com.java2nb.novel.messaging;

import com.java2nb.novel.mapper.FrontBookMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists all increments from one consumed Kafka batch in one transaction.
 */
@Component
@RequiredArgsConstructor
public class BookVisitBatchWriter {

    private final FrontBookMapper bookMapper;

    @Transactional(rollbackFor = Exception.class)
    public void write(Map<Long, Long> totals) {
        totals.forEach(bookMapper::addVisitCount);
    }
}
