package org.javaup.agent.parser;

import org.javaup.agent.model.RecommendationCriteria;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedRecommendationIntentParser implements RecommendationIntentParser {

    private static final Pattern PEOPLE_PATTERN = Pattern.compile("(\\d+)\\s*(个)?人");
    private static final Pattern RADIUS_KM_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*公里");
    private static final Pattern RADIUS_M_PATTERN = Pattern.compile("(\\d+)\\s*米");
    private static final Pattern UNDER_BUDGET_PATTERN = Pattern.compile("(\\d+)\\s*元?\\s*(以下|以内|内|之内)");
    private static final Pattern BETWEEN_BUDGET_PATTERN = Pattern.compile("(\\d+)\\s*(?:元)?\\s*(?:到|-|至|~)\\s*(\\d+)\\s*元?");
    private static final Pattern TIME_RANGE_PATTERN = Pattern.compile("(\\d{1,2})\\s*(?:点|:)\\s*(?:\\d{1,2})?\\s*(?:到|-|至|~)\\s*(\\d{1,2})\\s*(?:点|:)?");

    @Override
    public RecommendationCriteria parse(String message) {
        String text = message == null ? "" : message;
        RecommendationCriteria criteria = new RecommendationCriteria()
                .setTypeId(resolveTypeId(text))
                .setTypeName(resolveTypeName(text))
                .setPeopleCount(resolvePeopleCount(text))
                .setRadiusMeters(resolveRadius(text))
                .setSortBy(resolveSortBy(text))
                .setNeedLocation(true);
        resolveBudget(text, criteria);
        resolveTimeRange(text, criteria);
        resolvePerCapitaBudget(criteria);
        return criteria;
    }

    private Long resolveTypeId(String text) {
        if (text.contains("KTV") || text.contains("ktv") || text.contains("唱歌")) {
            return 2L;
        }
        if (text.contains("美食") || text.contains("吃饭") || text.contains("餐厅")) {
            return 1L;
        }
        return null;
    }

    private String resolveTypeName(String text) {
        if (text.contains("KTV") || text.contains("ktv") || text.contains("唱歌")) {
            return "KTV";
        }
        if (text.contains("美食") || text.contains("吃饭") || text.contains("餐厅")) {
            return "美食";
        }
        return null;
    }

    private Integer resolvePeopleCount(String text) {
        Matcher matcher = PEOPLE_PATTERN.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        if (text.contains("我和我父母") || text.contains("我和父母") || text.contains("和我爸妈")) {
            return 3;
        }
        if (text.contains("父母")) {
            return 3;
        }
        if (text.contains("情侣") || text.contains("两个人") || text.contains("2人")) {
            return 2;
        }
        return 1;
    }

    private Integer resolveRadius(String text) {
        Matcher kmMatcher = RADIUS_KM_PATTERN.matcher(text);
        if (kmMatcher.find()) {
            return (int) (Double.parseDouble(kmMatcher.group(1)) * 1000);
        }
        Matcher mMatcher = RADIUS_M_PATTERN.matcher(text);
        if (mMatcher.find()) {
            return Integer.parseInt(mMatcher.group(1));
        }
        return 5000;
    }

    private String resolveSortBy(String text) {
        if (text.contains("评分高") || text.contains("评价好") || text.contains("口碑好")) {
            return "compositeScore";
        }
        if (text.contains("距离近") || text.contains("最近")) {
            return "distance";
        }
        if (text.contains("便宜") || text.contains("实惠")) {
            return "price";
        }
        return "compositeScore";
    }

    private void resolveBudget(String text, RecommendationCriteria criteria) {
        Matcher betweenMatcher = BETWEEN_BUDGET_PATTERN.matcher(text);
        if (betweenMatcher.find()) {
            criteria.setBudgetMin(Long.parseLong(betweenMatcher.group(1)));
            criteria.setBudgetMax(Long.parseLong(betweenMatcher.group(2)));
            return;
        }
        Matcher underMatcher = UNDER_BUDGET_PATTERN.matcher(text);
        if (underMatcher.find()) {
            criteria.setBudgetMax(Long.parseLong(underMatcher.group(1)));
        }
    }

    private void resolveTimeRange(String text, RecommendationCriteria criteria) {
        Matcher matcher = TIME_RANGE_PATTERN.matcher(text);
        if (matcher.find()) {
            criteria.setStartTime(normalizeHour(matcher.group(1)));
            criteria.setEndTime(normalizeHour(matcher.group(2)));
        }
    }

    private String normalizeHour(String rawHour) {
        int hour = Integer.parseInt(rawHour);
        return String.format("%02d:00", hour);
    }

    private void resolvePerCapitaBudget(RecommendationCriteria criteria) {
        int peopleCount = criteria.getPeopleCount() == null || criteria.getPeopleCount() <= 0
                ? 1
                : criteria.getPeopleCount();
        if (criteria.getBudgetMin() != null) {
            criteria.setPerCapitaBudgetMin((long) Math.ceil(criteria.getBudgetMin() * 1.0 / peopleCount));
        }
        if (criteria.getBudgetMax() != null) {
            criteria.setPerCapitaBudgetMax(criteria.getBudgetMax() / peopleCount);
        }
    }
}
