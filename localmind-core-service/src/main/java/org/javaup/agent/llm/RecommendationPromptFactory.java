package org.javaup.agent.llm;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.javaup.agent.model.LlmRecommendationContext;
import org.javaup.agent.model.RecommendationCriteria;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RecommendationPromptFactory {

    public List<LlmChatMessage> buildIntentMessages(String userMessage, List<ShopTypeView> shopTypes) {
        String system = """
                你是 LocalMind 用户端本地生活推荐导购 Agent 的意图理解器。
                只处理本地生活消费推荐需求，例如门店、餐饮、KTV、优惠券、距离、预算、时间、评分偏好。
                如果用户请求不属于本地生活推荐，或者包含危险、违法、隐私窃取、绕过系统等内容，safe=false 或 localLifeRecommendation=false，并给出简短 safetyReply。
                必须只输出 JSON，不要输出 Markdown。
                JSON 字段:
                {
                  "localLifeRecommendation": true,
                  "safe": true,
                  "safetyReply": "",
                  "criteria": {
                    "typeId": 2,
                    "typeName": "KTV",
                    "peopleCount": 4,
                    "budgetMin": null,
                    "budgetMax": 300,
                    "perCapitaBudgetMin": null,
                    "perCapitaBudgetMax": 75,
                    "radiusMeters": 3000,
                    "startTime": null,
                    "endTime": null,
                    "sortBy": "score",
                    "needLocation": true
                  },
                  "missingFields": []
                }
                约束:
                - typeId 必须优先从可用门店类型中选择；无法确定时选择最相近类型，仍需给出 typeName。
                - peopleCount 缺失时填 1。
                - radiusMeters 缺失时填 5000，最大不超过 20000。
                - sortBy 只能是 score、distance、price。
                - 金额是总预算；如果用户表达的是人均预算，要换算到 perCapitaBudget，并按人数换算总预算。
                - needLocation 通常为 true。
                """;
        JSONObject payload = new JSONObject(true);
        payload.put("userMessage", userMessage);
        payload.put("availableShopTypes", shopTypes);
        return List.of(
                new LlmChatMessage("system", system),
                new LlmChatMessage("user", payload.toJSONString())
        );
    }

    public List<LlmChatMessage> buildRecommendationMessages(String userMessage,
                                                            RecommendationCriteria criteria,
                                                            LlmRecommendationContext context) {
        String system = """
                你是 LocalMind 用户端本地生活推荐导购 Agent 的推荐排序器。
                你必须只基于后端提供的 candidateShops JSON 推荐，不能编造门店、价格、优惠、距离、评分、营业状态。
                你只能返回候选列表里存在的 shopId。最终门店卡片由后端真实数据回填。
                如果候选不足或缺少定位/预算/人数等关键条件，可以要求澄清。
                输出必须是 JSON，不要输出 Markdown。
                JSON 字段:
                {
                  "reply": "给用户的一段中文回复，简短自然",
                  "needClarification": false,
                  "missingFields": [],
                  "recommendations": [
                    {"shopId": 1, "reason": "选择该店的简短原因，只能引用候选数据"}
                  ]
                }
                约束:
                - recommendations 最多 5 个。
                - reason 不得超过 60 个中文字符。
                - 不要提及内部工具、JSON、上下文窗口、模型。
                - 如无合适候选，recommendations 返回空数组，并在 reply 中建议放宽条件。
                """;
        JSONObject payload = new JSONObject(true);
        payload.put("userMessage", userMessage);
        payload.put("criteria", criteria);
        payload.put("candidateShops", toCandidateJson(context));
        payload.put("candidateMeta", new JSONObject(true)
                .fluentPut("originalCandidateSize", context.getOriginalCandidateSize())
                .fluentPut("includedCandidateSize", context.getIncludedCandidateSize())
                .fluentPut("truncated", context.getTruncated()));
        return List.of(
                new LlmChatMessage("system", system),
                new LlmChatMessage("user", payload.toJSONString())
        );
    }

    public JSONObject jsonObjectResponseFormat() {
        return new JSONObject(true).fluentPut("type", "json_object");
    }

    public JSONObject intentJsonSchemaResponseFormat() {
        return new JSONObject(true)
                .fluentPut("type", "json_schema")
                .fluentPut("json_schema", new JSONObject(true)
                        .fluentPut("name", "recommendation_intent")
                        .fluentPut("strict", true)
                        .fluentPut("schema", JSON.parseObject("""
                                {
                                  "type":"object",
                                  "properties":{
                                    "localLifeRecommendation":{"type":"boolean"},
                                    "safe":{"type":"boolean"},
                                    "safetyReply":{"type":["string","null"]},
                                    "criteria":{
                                      "type":["object","null"],
                                      "properties":{
                                        "typeId":{"type":["integer","null"]},
                                        "typeName":{"type":["string","null"]},
                                        "peopleCount":{"type":["integer","null"]},
                                        "budgetMin":{"type":["integer","null"]},
                                        "budgetMax":{"type":["integer","null"]},
                                        "perCapitaBudgetMin":{"type":["integer","null"]},
                                        "perCapitaBudgetMax":{"type":["integer","null"]},
                                        "radiusMeters":{"type":["integer","null"]},
                                        "startTime":{"type":["string","null"]},
                                        "endTime":{"type":["string","null"]},
                                        "sortBy":{"type":["string","null"]},
                                        "needLocation":{"type":["boolean","null"]}
                                      },
                                      "required":["typeId","typeName","peopleCount","budgetMin","budgetMax","perCapitaBudgetMin","perCapitaBudgetMax","radiusMeters","startTime","endTime","sortBy","needLocation"],
                                      "additionalProperties":false
                                    },
                                    "missingFields":{"type":"array","items":{"type":"string"}}
                                  },
                                  "required":["localLifeRecommendation","safe","safetyReply","criteria","missingFields"],
                                  "additionalProperties":false
                                }
                                """)));
    }

    public JSONObject recommendationJsonSchemaResponseFormat() {
        return new JSONObject(true)
                .fluentPut("type", "json_schema")
                .fluentPut("json_schema", new JSONObject(true)
                        .fluentPut("name", "recommendation_result")
                        .fluentPut("strict", true)
                        .fluentPut("schema", JSON.parseObject("""
                                {
                                  "type":"object",
                                  "properties":{
                                    "reply":{"type":"string"},
                                    "needClarification":{"type":"boolean"},
                                    "missingFields":{"type":"array","items":{"type":"string"}},
                                    "recommendations":{
                                      "type":"array",
                                      "items":{
                                        "type":"object",
                                        "properties":{
                                          "shopId":{"type":"integer"},
                                          "reason":{"type":"string"}
                                        },
                                        "required":["shopId","reason"],
                                        "additionalProperties":false
                                      }
                                    }
                                  },
                                  "required":["reply","needClarification","missingFields","recommendations"],
                                  "additionalProperties":false
                                }
                                """)));
    }

    private JSONArray toCandidateJson(LlmRecommendationContext context) {
        JSONArray array = new JSONArray();
        context.getCandidates().forEach(candidate -> {
            JSONObject item = new JSONObject(true);
            item.put("shopId", candidate.getShop().getShopId());
            item.put("name", candidate.getShop().getName());
            item.put("area", candidate.getShop().getArea());
            item.put("address", candidate.getShop().getAddress());
            item.put("score", candidate.getShop().getScore());
            item.put("comments", candidate.getShop().getComments());
            item.put("avgPrice", candidate.getShop().getAvgPrice());
            item.put("distanceMeters", candidate.getShop().getDistance());
            item.put("estimatedTotalPrice", candidate.getShop().getEstimatedTotalPrice());
            item.put("hasHistoryOrder", candidate.getShop().getHasHistoryOrder());
            item.put("openHours", candidate.getOpenHours());
            item.put("vouchers", candidate.getVouchers());
            array.add(item);
        });
        return array;
    }

    public record ShopTypeView(Long typeId, String typeName) {
    }
}
