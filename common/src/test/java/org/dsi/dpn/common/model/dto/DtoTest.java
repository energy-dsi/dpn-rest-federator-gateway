// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Builder/accessor coverage for the Management Node payload DTOs. */
class DtoTest {

    @Test @DisplayName("AttributesDTO builder + getters")
    void attributes() {
        AttributesDTO a = AttributesDTO.builder().name("k").value("v").type("string").build();
        assertThat(a.getName()).isEqualTo("k");
        assertThat(a.getValue()).isEqualTo("v");
        assertThat(a.getType()).isEqualTo("string");
    }

    @Test @DisplayName("ConsumerDTO builder + getters")
    void consumer() {
        ConsumerDTO c = ConsumerDTO.builder()
                .id(1L).name("Consumer A").orgId(9L)
                .scheduleType("cron").scheduleExpression("* * * * *")
                .idpClientId("client-a").build();
        assertThat(c.getId()).isEqualTo(1L);
        assertThat(c.getName()).isEqualTo("Consumer A");
        assertThat(c.getOrgId()).isEqualTo(9L);
        assertThat(c.getIdpClientId()).isEqualTo("client-a");
        assertThat(c.getScheduleType()).isEqualTo("cron");
        assertThat(c.getScheduleExpression()).isEqualTo("* * * * *");
    }

    @Test @DisplayName("ProductDTO builder + getters, including nested consumers")
    void product() {
        ConsumerDTO consumer = ConsumerDTO.builder().idpClientId("client-a").build();
        ProductDTO p = ProductDTO.builder()
                .id(2L).producerId(3L).name("FMAR Asset Registration")
                .topic("[{\"request_type\":\"GET\",\"request_path\":\"/api/v1/fmar/assets\"}]")
                .source("src").type("rest")
                .consumers(List.of(consumer)).build();
        assertThat(p.getId()).isEqualTo(2L);
        assertThat(p.getProducerId()).isEqualTo(3L);
        assertThat(p.getName()).isEqualTo("FMAR Asset Registration");
        assertThat(p.getType()).isEqualTo("rest");
        assertThat(p.getTopic()).contains("/api/v1/fmar/assets");
        assertThat(p.getConsumers()).hasSize(1);
        assertThat(p.getConsumers().get(0).getIdpClientId()).isEqualTo("client-a");
    }

    @Test @DisplayName("ProducerDTO builder + getters, including nested products")
    void producer() {
        ProductDTO product = ProductDTO.builder().name("P1").type("rest").build();
        ProducerDTO prod = ProducerDTO.builder()
                .id(4L).name("DPN01-PRODUCER").description("desc").orgId(1L).active(true)
                .host("dpn-local-01").port(BigDecimal.valueOf(8443)).tls(true)
                .idpClientId("FEDERATOR_DPN01").products(List.of(product)).build();
        assertThat(prod.getName()).isEqualTo("DPN01-PRODUCER");
        assertThat(prod.getHost()).isEqualTo("dpn-local-01");
        assertThat(prod.getPort()).isEqualByComparingTo(BigDecimal.valueOf(8443));
        assertThat(prod.getTls()).isTrue();
        assertThat(prod.getActive()).isTrue();
        assertThat(prod.getIdpClientId()).isEqualTo("FEDERATOR_DPN01");
        assertThat(prod.getProducts()).hasSize(1);
        assertThat(prod.getProducts().get(0).getName()).isEqualTo("P1");
    }

    @Test @DisplayName("ConsumerConfigDTO builder + getters, including nested producers")
    void consumerConfig() {
        ProducerDTO producer = ProducerDTO.builder().name("DPN01-PRODUCER").build();
        ConsumerConfigDTO cfg = ConsumerConfigDTO.builder()
                .clientId("client-a").name("Consumer A")
                .scheduleType("cron").scheduleExpression("* * * * *")
                .producers(List.of(producer)).build();
        assertThat(cfg.getClientId()).isEqualTo("client-a");
        assertThat(cfg.getName()).isEqualTo("Consumer A");
        assertThat(cfg.getProducers()).hasSize(1);
        assertThat(cfg.getProducers().get(0).getName()).isEqualTo("DPN01-PRODUCER");
    }

    @Test @DisplayName("ProductConsumerDTO builder + getters")
    void productConsumer() {
        ProductConsumerDTO pc = ProductConsumerDTO.builder()
                .productId(2L).consumerId(1L).validity(BigDecimal.TEN)
                .scheduleType("cron").scheduleExpression("* * * * *").destination("dest").build();
        assertThat(pc.getProductId()).isEqualTo(2L);
        assertThat(pc.getConsumerId()).isEqualTo(1L);
        assertThat(pc.getValidity()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(pc.getDestination()).isEqualTo("dest");
    }
}
