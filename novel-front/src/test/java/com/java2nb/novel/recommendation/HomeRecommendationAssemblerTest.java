package com.java2nb.novel.recommendation;

import com.java2nb.novel.vo.BookSettingVO;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HomeRecommendationAssemblerTest {
    private final HomeRecommendationAssembler assembler = new HomeRecommendationAssembler();

    @Test
    void sortsWeeklyAndHotByTheirOwnWindowInsteadOfTransportOrder() {
        var home = assembler.compose(List.of(candidate(12,30,300,0), candidate(11,90,90,0),
            candidate(13,0,240,0)), List.of());
        assertThat(ids(home,"2")).containsExactly(11L,12L);
        assertThat(ids(home,"3")).containsExactly(13L,12L,11L);
    }

    @Test
    void readingSecondsOutrankClicksAndTiesUseClicksThenId() {
        var home = assembler.compose(List.of(candidate(4,30,30,100), candidate(3,30,30,100),
            candidate(2,30,30,200), candidate(1,90,90,0)), List.of());
        assertThat(ids(home,"2")).containsExactly(1L,2L,3L,4L);
        assertThat(ids(home,"3")).containsExactly(1L,2L,3L,4L);
    }

    @Test
    void enoughHotCandidatesAvoidAllFinalWeeklyBooks() {
        var candidates = new ArrayList<PopularBookCandidate>();
        for (int id = 1; id <= 11; id++) candidates.add(candidate(id,id <= 5 ? 60 : 0,120-id,0));
        var home = assembler.compose(candidates, List.of());
        assertThat(ids(home,"2")).containsExactly(1L,2L,3L,4L,5L);
        assertThat(ids(home,"3")).containsExactly(6L,7L,8L,9L,10L,11L);
    }

    @Test
    void independentConfiguredHotBooksPrecedeOverlappingAlgorithmBooks() {
        var home = assembler.compose(List.of(candidate(1,90,90,0), candidate(2,0,60,0)),
            List.of(configured(3,3),configured(4,3),configured(5,3),configured(6,3),configured(7,3)));
        assertThat(ids(home,"2")).containsExactly(1L);
        assertThat(ids(home,"3")).containsExactly(2L,3L,4L,5L,6L,7L);
    }

    @Test
    void shortageAllowsCrossGroupOverlapButNeverWithinGroupDuplicates() {
        var one = candidate(1,90,90,0);
        var home = assembler.compose(List.of(one,one,candidate(2,0,60,0)),
            List.of(configured(1,2),configured(1,3),configured(2,3),configured(3,3),configured(3,3)));
        assertThat(ids(home,"2")).containsExactly(1L);
        assertThat(ids(home,"3")).containsExactly(2L,3L,1L);
    }

    @Test
    void hotAvoidsWeeklyConfiguredFallbackToo() {
        var home = assembler.compose(List.of(candidate(1,0,300,0),candidate(2,0,240,0)),
            List.of(configured(1,2),configured(3,3)));
        assertThat(ids(home,"2")).containsExactly(1L);
        assertThat(ids(home,"3")).containsExactly(2L,3L,1L);
    }

    @Test
    void fallbackUsesOnlyItsOwnConfiguredGroupAndCanRemainShort() {
        var home = assembler.compose(List.of(), List.of(configured(1,2),configured(2,3),
            configured(3,0),configured(4,1),configured(5,4)));
        assertThat(ids(home,"2")).containsExactly(1L);
        assertThat(ids(home,"3")).containsExactly(2L);
    }

    @Test
    void manualGroupsPreserveFieldsOrderAndCrossGroupRepeats() {
        var first = configured(7,0); first.setSort((byte)9);
        var second = configured(6,0); second.setSort((byte)10);
        var text = configured(7,1); var classic = configured(7,4);
        var home = assembler.compose(List.of(candidate(7,90,90,0)),List.of(first,second,text,classic));
        assertThat(ids(home,"0")).containsExactly(7L,6L);
        assertThat(ids(home,"1")).containsExactly(7L);
        assertThat(ids(home,"4")).containsExactly(7L);
        assertThat(home.get("0").get(0)).usingRecursiveComparison().isEqualTo(first);
        assertThat(home.get("0").get(1)).usingRecursiveComparison().isEqualTo(second);
        assertThat(home.get("1").get(0)).usingRecursiveComparison().isEqualTo(text);
        assertThat(home.get("4").get(0)).usingRecursiveComparison().isEqualTo(classic);
    }

    @Test
    void capsAllFiveGroupsAndIgnoresUnknownConfiguredTypes() {
        var configured = new ArrayList<BookSettingVO>();
        for (int type = 0; type <= 5; type++) {
            for (int id = 1; id <= 12; id++) configured.add(configured(type * 100 + id,type));
        }
        var home = assembler.configuredOnly(configured);
        assertThat(home.keySet()).containsExactly("0","1","2","3","4");
        assertThat(home.get("0")).hasSize(4);
        assertThat(home.get("1")).hasSize(10);
        assertThat(home.get("2")).hasSize(5);
        assertThat(home.get("3")).hasSize(6);
        assertThat(home.get("4")).hasSize(6);
    }

    @Test
    void outputMutationCannotCorruptInputIncludingMutableDates() {
        var candidate = candidate(1,90,90,0);
        candidate.setType((byte)4); candidate.setSort((byte)9);
        var manual = configured(2,0);
        var home = assembler.compose(List.of(candidate), List.of(manual));
        assertThat(home.get("2")).hasSize(1);
        assertThat(home.get("0")).hasSize(1);
        assertThat(home.get("3")).hasSize(1);
        home.get("2").get(0).setBookName("changed");
        home.get("2").get(0).getCreateTime().setTime(9999);
        home.get("0").get(0).getUpdateTime().setTime(9999);
        home.get("3").get(0).setBookDesc("changed");
        assertThat(candidate.getBookName()).isEqualTo("book1");
        assertThat(candidate.getBookDesc()).isEqualTo("description");
        assertThat(candidate.getCreateTime()).isEqualTo(new Date(1000));
        assertThat(candidate.getType()).isEqualTo((byte)4);
        assertThat(candidate.getSort()).isEqualTo((byte)9);
        assertThat(manual.getUpdateTime()).isEqualTo(new Date(2000));
        assertThat(home.get("3").get(0).getCreateTime()).isEqualTo(new Date(1000));
    }

    @Test
    void assignsContiguousAlgorithmSlotsOnCopies() {
        var home = assembler.compose(List.of(candidate(2,90,90,0)),List.of(configured(1,2)));
        assertThat(home.get("2")).extracting(BookSettingVO::getType).containsExactly((byte)2,(byte)2);
        assertThat(home.get("2")).extracting(BookSettingVO::getSort).containsExactly((byte)1,(byte)2);
        assertThat(home.get("3")).extracting(BookSettingVO::getType).containsExactly((byte)3);
        assertThat(home.get("3")).extracting(BookSettingVO::getSort).containsExactly((byte)1);
    }

    @Test
    void hugeSecondsDoNotOverflowAndZeroWindowsDoNotBecomeAlgorithmPicks() {
        var big = candidate(2,1,1,0); big.setWeekSeconds(new BigInteger("18446744073709551616"));
        big.setHotSeconds(new BigInteger("18446744073709551616"));
        var home = assembler.compose(List.of(candidate(3,0,0,999),candidate(1,90,90,0),big),List.of());
        assertThat(ids(home,"2")).containsExactly(2L,1L);
        assertThat(ids(home,"3")).containsExactly(2L,1L);
    }

    @Test
    void emptyInputStillReturnsAllFiveGroups() {
        var home = assembler.compose(List.of(),List.of());
        assertThat(home.keySet()).containsExactly("0","1","2","3","4");
        assertThat(home.values()).allSatisfy(group -> assertThat(group).isEmpty());
        assertThat(assembler.configuredOnly(List.of()).keySet()).containsExactly("0","1","2","3","4");
    }

    private List<Long> ids(Map<String,List<BookSettingVO>> home, String type) {
        return home.get(type).stream().map(BookSettingVO::getBookId).toList();
    }

    private PopularBookCandidate candidate(long id,long week,long hot,long clicks) {
        var row = new PopularBookCandidate(); metadata(row,id);
        row.setWeekSeconds(BigInteger.valueOf(week)); row.setHotSeconds(BigInteger.valueOf(hot));
        row.setVisitCount(clicks); return row;
    }

    private BookSettingVO configured(long id,int type) {
        var row = new BookSettingVO(); metadata(row,id);
        row.setType((byte)type); row.setSort((byte)5); return row;
    }

    private void metadata(BookSettingVO row,long id) {
        row.setId(id + 1000); row.setBookId(id); row.setBookName("book"+id); row.setPicUrl("cover");
        row.setAuthorName("author"); row.setBookDesc("description"); row.setScore(4.5F);
        row.setCatId(1); row.setCatName("fiction"); row.setBookStatus((byte)1);
        row.setCreateTime(new Date(1000)); row.setUpdateTime(new Date(2000));
        row.setCreateUserId(10L); row.setUpdateUserId(20L);
    }
}
