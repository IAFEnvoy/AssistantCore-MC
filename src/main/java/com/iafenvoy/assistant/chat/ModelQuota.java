package com.iafenvoy.assistant.chat;

import com.iafenvoy.assistant.AssistantException;
import com.iafenvoy.assistant.AssistantException.Reason;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Daily admission counts only; cumulative billed usage remains in world SavedData.
 */
public final class ModelQuota {
    public static final MapCodec<ModelQuota> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.optionalFieldOf("usageDate", "").forGetter(v -> v.usageDate),
            Codec.INT.optionalFieldOf("usedModelCalls", 0).forGetter(v -> v.usedModelCalls)
    ).apply(i, ModelQuota::new));
    private String usageDate;
    private int usedModelCalls;

    public ModelQuota() {
        this("", 0);
    }

    private ModelQuota(String usageDate, int usedModelCalls) {
        this.usageDate = usageDate;
        this.usedModelCalls = usedModelCalls;
    }

    public void reserve(String date, int dailyLimit) {
        if (!date.equals(this.usageDate)) {
            this.usageDate = date;
            this.usedModelCalls = 0;
        }
        if (this.usedModelCalls >= dailyLimit) throw new AssistantException(Reason.RATE_LIMITED);
        this.usedModelCalls++;
    }

    public ModelQuota copy() {
        return new ModelQuota(this.usageDate, this.usedModelCalls);
    }
}
