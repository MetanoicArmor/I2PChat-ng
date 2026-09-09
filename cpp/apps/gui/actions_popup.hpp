#pragma once

#include <QColor>
#include <QFrame>
#include <QPoint>
#include <functional>

class QLabel;
class QVBoxLayout;

namespace i2pchat::gui {

class ActionsPopup;

class ActionsPopupItem : public QFrame {
    Q_OBJECT
public:
    explicit ActionsPopupItem(const QString& title, const QString& shortcut,
                              const QString& tooltip, QWidget* parent);

    void set_title(const QString& title);
    void apply_row_colors(bool night);
    void set_host(ActionsPopup* host) { host_ = host; }

signals:
    void clicked();

protected:
    void mouseReleaseEvent(QMouseEvent* event) override;
    void enterEvent(QEnterEvent* event) override;
    void leaveEvent(QEvent* event) override;
    void paintEvent(QPaintEvent* event) override;

private:
    void apply_item_fonts();

    QLabel* title_label_ = nullptr;
    QLabel* shortcut_label_ = nullptr;
    ActionsPopup* host_ = nullptr;
    bool hover_ = false;
    bool night_ = false;
};

class ActionsPopup : public QFrame {
    Q_OBJECT
public:
    explicit ActionsPopup(QWidget* parent = nullptr);

    void clear_actions();
    ActionsPopupItem* add_action(const QString& title, const QString& shortcut,
                                 const std::function<void()>& callback,
                                 const QString& tooltip = {});
    void add_separator();
    void show_below(QWidget* anchor);
    void show_at(const QPoint& global_pos);
    void set_night(bool night);
    void cancel_keyboard_highlight();

protected:
    void paintEvent(QPaintEvent* event) override;
    void showEvent(QShowEvent* event) override;
    void hideEvent(QHideEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;

private:
    void apply_theme();
    void refresh_row_colors();

    QFrame* surface_ = nullptr;
    QVBoxLayout* surface_layout_ = nullptr;
    bool night_ = false;
    bool dwm_patched_ = false;
    QColor popup_bg_{246, 247, 250};
    QColor popup_border_{208, 211, 218};
};

}  // namespace i2pchat::gui
