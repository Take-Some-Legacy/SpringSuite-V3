package com.takesome.springsuite.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class DesktopAgentUiTest {
    @Test
    void formatsRussianFieldCounts() {
        assertThat(DesktopAgentUi.actionCountLabel(0)).isEqualTo("0 полей");
        assertThat(DesktopAgentUi.actionCountLabel(1)).isEqualTo("1 поле");
        assertThat(DesktopAgentUi.actionCountLabel(2)).isEqualTo("2 поля");
        assertThat(DesktopAgentUi.actionCountLabel(5)).isEqualTo("5 полей");
        assertThat(DesktopAgentUi.actionCountLabel(11)).isEqualTo("11 полей");
        assertThat(DesktopAgentUi.actionCountLabel(21)).isEqualTo("21 поле");
    }

    @Test
    void describesReadyAutofillWithoutChangingPrimaryActionMeaning() {
        assertThat(DesktopAgentUi.headerSubtitle(3, true))
                .isEqualTo("● Автозаполнение готово · 3 поля");
        assertThat(DesktopAgentUi.headerSubtitle(0, true))
                .isEqualTo("Веб-форма распознана · нет готовых значений");
    }

    @Test
    void describesActiveFieldUsingPlaceholderThenNearestContext() {
        assertThat(DesktopAgentUi.activeFieldDetail("Номер телефона", "Введите номер для входа", "tel"))
                .isEqualTo("Placeholder: Номер телефона");
        assertThat(DesktopAgentUi.activeFieldDetail("", "Введите номер для входа", "tel"))
                .isEqualTo("Контекст: Введите номер для входа");
        assertThat(DesktopAgentUi.activeFieldDetail("", "", "text"))
                .isEqualTo("Тип: text · placeholder отсутствует");
    }

    @Test
    void labelsDetectedFieldsButton() {
        assertThat(DesktopAgentUi.detectedFieldsLabel(0)).isEqualTo("Поля: 0");
        assertThat(DesktopAgentUi.detectedFieldsLabel(7)).isEqualTo("Поля: 7");
    }
    @Test
    void replacesOverlayHostContentWithoutReplacingTheWindowContentPane() {
        JPanel host = new JPanel();
        JPanel first = new JPanel();
        first.add(new JLabel("first"));
        JPanel second = new JPanel();
        second.add(new JLabel("second"));

        DesktopAgentUi.replaceHostContent(host, first);
        DesktopAgentUi.replaceHostContent(host, second);

        assertThat(host.getComponentCount()).isEqualTo(1);
        assertThat(host.getComponent(0)).isSameAs(second);
    }

    @Test
    void keepsAiFillActionAvailableWithoutPreparedValues() {
        assertThat(DesktopAgentUi.fillActionAvailable(0, "ai")).isTrue();
        assertThat(DesktopAgentUi.fillActionAvailable(0, "memory")).isFalse();
        assertThat(DesktopAgentUi.fillActionAvailable(1, "memory")).isTrue();
        assertThat(DesktopAgentUi.fillActionAvailable(0, "chatgpt-5.6")).isTrue();
        assertThat(DesktopAgentUi.fillActionAvailable(1, "chatgpt-5.6")).isTrue();
    }

    @Test
    void animatesLoadingTextWhileAiValueIsPending() {
        assertThat(DesktopAgentUi.loadingButtonText("Загрузка", 0)).isEqualTo("Загрузка");
        assertThat(DesktopAgentUi.loadingButtonText("Загрузка", 1)).isEqualTo("Загрузка.");
        assertThat(DesktopAgentUi.loadingButtonText("Загрузка", 2)).isEqualTo("Загрузка..");
        assertThat(DesktopAgentUi.loadingButtonText("Загрузка", 3)).isEqualTo("Загрузка...");
        assertThat(DesktopAgentUi.loadingButtonText("Загрузка", 4)).isEqualTo("Загрузка");
    }

}
