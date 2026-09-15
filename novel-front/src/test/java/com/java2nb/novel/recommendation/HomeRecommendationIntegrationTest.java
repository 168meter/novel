package com.java2nb.novel.recommendation;

import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.mapper.FrontBookSettingMapper;
import com.java2nb.novel.service.impl.BookServiceImpl;
import com.java2nb.novel.vo.BookVO;
import com.java2nb.novel.vo.BookSettingVO;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HomeRecommendationIntegrationTest {
    @Test void bookServiceDelegatesHomeGroupsWithoutReadingOrInitializingLegacySettings() throws Exception {
        HomeRecommendationService recommendation=mock(HomeRecommendationService.class);
        Map<String,List<BookSettingVO>> expected=Map.of("0",List.of(),"1",List.of(),"2",List.of(),"3",List.of(),"4",List.of());
        when(recommendation.getHome()).thenReturn(expected);
        Fixture fixture=newFixture(recommendation);
        assertThat(fixture.service().listBookSettingVO()).isSameAs(expected);
        verify(recommendation).getHome();
        verifyNoInteractions(fixture.legacyMapper());
    }

    @Test void existingHomepageRankMethodsRemainIndependentFromRecommendations() throws Exception {
        HomeRecommendationService recommendation=mock(HomeRecommendationService.class);
        Fixture fixture=newFixture(recommendation);
        Book rankedBook=new Book();
        BookVO updatedBook=new BookVO();
        doReturn(List.of(rankedBook)).when(fixture.cache()).getList(anyString(),eq(Book.class));
        doReturn(List.of(updatedBook)).when(fixture.cache()).getList(anyString(),eq(BookVO.class));

        assertThat(fixture.service().listClickRank()).containsExactly(rankedBook);
        assertThat(fixture.service().listNewRank()).containsExactly(rankedBook);
        assertThat(fixture.service().listUpdateRank()).containsExactly(updatedBook);
        verifyNoInteractions(recommendation);
    }

    private Fixture newFixture(HomeRecommendationService recommendation) throws Exception {
        Constructor<?> constructor=BookServiceImpl.class.getDeclaredConstructors()[0];
        Object[] arguments=new Object[constructor.getParameterCount()];
        FrontBookSettingMapper legacyMapper=null;
        CacheService cache=null;
        boolean recommendationBoundaryFound=false;
        for(int i=0;i<arguments.length;i++) {
            Class<?> type=constructor.getParameterTypes()[i];
            if(type==HomeRecommendationService.class) { arguments[i]=recommendation; recommendationBoundaryFound=true; }
            else {
                arguments[i]=mock(type);
                if(type==FrontBookSettingMapper.class) legacyMapper=(FrontBookSettingMapper)arguments[i];
                if(type==CacheService.class) cache=(CacheService)arguments[i];
            }
        }
        assertThat(recommendationBoundaryFound).as("BookServiceImpl constructor must own the new boundary").isTrue();
        return new Fixture((BookServiceImpl)constructor.newInstance(arguments),legacyMapper,cache);
    }

    private record Fixture(BookServiceImpl service,FrontBookSettingMapper legacyMapper,CacheService cache) {}
}
