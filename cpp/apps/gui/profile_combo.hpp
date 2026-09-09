#pragma once

#include <QComboBox>
#include <QFrame>
#include <QStringList>
#include <QWidget>

class QLabel;
class QListWidget;

namespace i2pchat::gui {

class RoundedVerticalScrollbar;

class ProfileComboBox : public QComboBox {
    Q_OBJECT
public:
    explicit ProfileComboBox(QWidget* parent = nullptr);

signals:
    void popupRequested();

protected:
    void showPopup() override;
};

class ProfileComboPopup : public QFrame {
    Q_OBJECT
public:
    explicit ProfileComboPopup(QWidget* parent = nullptr, bool as_embedded = false);

    void apply_theme(bool night);
    void set_items(const QStringList& values, const QString& selected);
    void show_below(QWidget* anchor, bool keep_editor_focus = false);
    [[nodiscard]] QListWidget* list() const { return list_; }

signals:
    void itemChosen(const QString& text);

protected:
    void paintEvent(QPaintEvent* event) override;
    void showEvent(QShowEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;

private:
    void size_to_anchor(QWidget* anchor);
    void sync_scrollbar();

    QFrame* surface_ = nullptr;
    QListWidget* list_ = nullptr;
    RoundedVerticalScrollbar* scrollbar_ = nullptr;
    bool embedded_ = false;
    bool dwm_patched_ = false;
    QColor bg_{246, 247, 250};
    QColor border_{208, 211, 218};
    QColor behind_{245, 245, 247};
};

class ProfileComboWithArrow : public QWidget {
    Q_OBJECT
public:
    explicit ProfileComboWithArrow(QWidget* parent = nullptr);

    [[nodiscard]] ProfileComboBox* combo() const { return combo_; }
    void set_arrow_color(const QString& color);
    void apply_theme(bool night);
    void enable_autocomplete();

protected:
    void resizeEvent(QResizeEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;

private:
    void show_custom_popup();
    void on_item_chosen(const QString& text);
    void on_typing(const QString& text);
    void hide_suggest_if_focus_left();
    [[nodiscard]] QStringList unique_values() const;
    [[nodiscard]] QStringList filtered_suggestions(const QString& needle) const;
    [[nodiscard]] QListWidget* list_for_editor_keys() const;
    bool handle_list_key(QListWidget* list, int key);

    ProfileComboBox* combo_ = nullptr;
    QLabel* arrow_ = nullptr;
    ProfileComboPopup* popup_ = nullptr;
    ProfileComboPopup* typing_panel_ = nullptr;
    bool typing_enabled_ = false;
    bool suppress_suggest_ = false;
};

}  // namespace i2pchat::gui
