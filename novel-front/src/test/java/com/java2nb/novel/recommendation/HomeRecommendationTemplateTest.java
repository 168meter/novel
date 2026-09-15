package com.java2nb.novel.recommendation;

import com.java2nb.novel.vo.BookSettingVO;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.FileTemplateResolver;
import static org.assertj.core.api.Assertions.assertThat;

class HomeRecommendationTemplateTest {
    @Test void pcRendersFiveGroupsAndNewsWithCurrentCaps() {
        var groups=groups();
        String html=render("index",Set.of("#carouseBig","#topBooks1","#topBooks2","#currentWeek","#hotRecBooks","#classicBooks","#indexNews"),groups);
        for(int type=0;type<=4;type++) for(BookSettingVO book:groups.get(Integer.toString(type)))
            assertThat(html).contains("/book/"+book.getBookId()+".html",book.getBookName());
        assertThat(html).contains("news-title","/about/newsInfo-99.html");
        assertThat(groups.get("2")).hasSize(5); assertThat(groups.get("3")).hasSize(6);
    }

    @Test void mobileRendersExistingHotGroupWithoutAddingWeeklySection() {
        String hot=render("mobile/index",Set.of("#hotRecBooks"),groups());
        for(int id=1;id<=6;id++) assertThat(hot).contains("hot-"+id);
        assertThat(render("mobile/index",Set.of("#currentWeek"),groups())).isBlank();
    }

    @Test void emptyGroupsRenderWithoutIndexErrors() {
        Map<String,List<BookSettingVO>> empty=new LinkedHashMap<>();
        for(int type=0;type<=4;type++) empty.put(Integer.toString(type),List.of());
        assertThat(render("index",Set.of("#topBooks1","#topBooks2","#currentWeek","#hotRecBooks","#classicBooks"),empty)).doesNotContain("/book/");
        assertThat(render("mobile/index",Set.of("#hotRecBooks"),empty)).doesNotContain("/book/");
    }

    private String render(String template,Set<String> selectors,Map<String,List<BookSettingVO>> groups) {
        FileTemplateResolver resolver=new FileTemplateResolver();
        resolver.setPrefix(Path.of("src","main","resources","templates").toAbsolutePath()+java.io.File.separator);
        resolver.setSuffix(".html"); resolver.setTemplateMode(TemplateMode.HTML); resolver.setCacheable(false);
        SpringTemplateEngine engine=new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        Context context=new Context(); context.setVariable("bookMap",groups);
        context.setVariable("newsList",List.of(Map.of("id",99L,"catName","notice","title","news-title")));
        return engine.process(new TemplateSpec(template,selectors,TemplateMode.HTML,null),context);
    }

    private Map<String,List<BookSettingVO>> groups() {
        Map<String,List<BookSettingVO>> result=new LinkedHashMap<>();
        int[] caps={4,10,5,6,6}; String[] names={"carousel-","text-","week-","hot-","classic-"};
        long next=1;
        for(int type=0;type<=4;type++) {
            var rows=new ArrayList<BookSettingVO>();
            for(int slot=1;slot<=caps[type];slot++) {
                var book=new BookSettingVO(); book.setBookId(next++); book.setType((byte)type); book.setSort((byte)slot);
                book.setBookName(names[type]+slot); book.setPicUrl("/cover/"+slot); book.setAuthorName("author"); book.setBookDesc("description"); rows.add(book);
            }
            result.put(Integer.toString(type),rows);
        }
        return result;
    }
}
