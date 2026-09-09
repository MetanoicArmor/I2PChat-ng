#pragma once

#include <QColor>
#include <QFrame>
#include <QHash>
#include <QIcon>
#include <QVector>
#include <filesystem>
#include <string>

class QHideEvent;
class QKeyEvent;
class QPaintEvent;
class QScrollArea;
class QShowEvent;
class QToolButton;

namespace i2pchat::gui {

class RoundedVerticalScrollbar;

[[nodiscard]] std::filesystem::path find_fluent_emoji_root();
[[nodiscard]] QIcon tinted_face_icon(bool dark);

class EmojiPickerPopup : public QFrame {
    Q_OBJECT
public:
    explicit EmojiPickerPopup(QWidget* parent = nullptr);

    void set_night(bool night);
    void show_above(QWidget* anchor);

protected:
    void keyPressEvent(QKeyEvent* event) override;
    void hideEvent(QHideEvent* event) override;
    void paintEvent(QPaintEvent* event) override;
    void showEvent(QShowEvent* event) override;

signals:
    void emoji_chosen(const QString& glyph);
    void picker_hidden();

private:
    void rebuild();
    void apply_theme();
    void sync_focus_visual();
    void sync_scrollbar();
    void pick_focused();
    QHash<QString, QString> png_by_glyph_;
    std::filesystem::path root_;
    QScrollArea* scroll_ = nullptr;
    QWidget* inner_ = nullptr;
    RoundedVerticalScrollbar* scrollbar_ = nullptr;
    QVector<QToolButton*> buttons_;
    int focus_idx_ = 0;
    bool night_ = false;
    bool dwm_patched_ = false;
    QColor popup_bg_{246, 247, 250};
    QColor popup_border_{208, 211, 218};
};

}  // namespace i2pchat::gui
