package com.homektv.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AI 自动应用策略：根据置信度阈值判断 AI 分类结果是否应该被自动应用。
 *
 * AI auto-apply policy: decides whether an AI classification result should be
 * automatically applied based on a confidence threshold.
 */
@Component
public class AiAutoApplyPolicy {
    private final AiConfigService configService;

    @Autowired
    public AiAutoApplyPolicy(AiConfigService configService) {
        this.configService = configService;
    }

    /**
     * 判断给定的 AI 歌曲分类结果是否满足自动应用的条件。
     *
     * Determines whether the given AI song classification result meets the
     * criteria for automatic application.
     *
     * @param result AI 歌曲分类结果 / the AI song classification result
     * @return {@code true} 如果结果非空且置信度达到阈值 / if non-null and confidence meets the threshold
     */
    public boolean shouldAutoApply(AiSongClassification result) {
        return result != null && result.confidence() >= threshold();
    }

    /**
     * 获取当前自动应用的置信度阈值，取值限定在 [0.0, 1.0] 区间内。
     *
     * Returns the current auto-apply confidence threshold, clamped to the
     * range [0.0, 1.0].
     *
     * @return 阈值，范围 [0.0, 1.0] / the threshold, in range [0.0, 1.0]
     */
    public double threshold() {
        return configService.resolve().classificationThreshold();
    }
}
