package org.javaup.agent.model;

import lombok.Data;
import lombok.experimental.Accessors;
import org.javaup.agent.vo.RecommendationShopVo;
import org.javaup.entity.Voucher;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class LlmRecommendationCandidate {

    private RecommendationShopVo shop;

    private String openHours;

    private List<VoucherDigest> vouchers = new ArrayList<>();

    public Long getShopId() {
        return shop == null ? null : shop.getShopId();
    }

    @Data
    @Accessors(chain = true)
    public static class VoucherDigest {

        private Long voucherId;

        private String title;

        private String subTitle;

        private Long payValue;

        private Long actualValue;

        private Integer type;

        public static VoucherDigest from(Voucher voucher) {
            return new VoucherDigest()
                    .setVoucherId(voucher.getId())
                    .setTitle(voucher.getTitle())
                    .setSubTitle(voucher.getSubTitle())
                    .setPayValue(voucher.getPayValue())
                    .setActualValue(voucher.getActualValue())
                    .setType(voucher.getType());
        }
    }
}
