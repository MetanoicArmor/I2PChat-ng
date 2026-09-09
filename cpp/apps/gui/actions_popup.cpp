#include "actions_popup.hpp"
#include "popup_chrome.hpp"

#include <algorithm>
#include <cmath>
#include <QApplication>
#include <QEnterEvent>
#include <QFont>
#include <QHideEvent>
#include <QHBoxLayout>
#include <QLabel>
#include <QMouseEvent>
#include <QPainter>
#include <QResizeEvent>
#include <QScreen>
#include <QShowEvent>
#include <QStyle>
#include <QTimer>
#include <QVBoxLayout>

namespace i2pchat::gui {
namespace {

constexpr qreal kOuterRadius = 14.0;

}  // namespace

ActionsPopupItem::ActionsPopupItem(const QString& title, const QString& shortcut,
                                   const QString& tooltip, QWidget* parent)
    : QFrame(parent) {
    setObjectName("ActionsPopupItem");
    setFrameShape(QFrame::NoFrame);
    setAttribute(Qt::WA_Hover, true);
    setAttribute(Qt::WA_StyledBackground, true);
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::NoFocus);
    if (!tooltip.isEmpty()) {
        setToolTip(tooltip);
    }
    auto* layout = new QHBoxLayout(this);
    layout->setContentsMargins(12, 5, 12, 5);
    layout->setSpacing(10);
    title_label_ = new QLabel(title, this);
    title_label_->setObjectName("ActionsPopupItemTitle");
    title_label_->setAttribute(Qt::WA_TransparentForMouseEvents, true);
    layout->addWidget(title_label_, 1);
    shortcut_label_ = new QLabel(shortcut, this);
    shortcut_label_->setObjectName("ActionsPopupItemShortcut");
    shortcut_label_->setAttribute(Qt::WA_TransparentForMouseEvents, true);
    shortcut_label_->setAlignment(Qt::AlignRight | Qt::AlignVCenter);
    shortcut_label_->setVisible(!shortcut.trimmed().isEmpty());
    layout->addWidget(shortcut_label_, 0);
    apply_item_fonts();
}

void ActionsPopupItem::apply_item_fonts() {
    QFont base = QApplication::font();
    if (base.pointSizeF() <= 0) {
        base.setPointSize(13);
    }
    title_label_->setFont(base);
    QFont sc = base;
    const qreal step = base.pointSizeF() > 10.5 ? 1.25 : 1.0;
    sc.setPointSizeF(std::max(9.0, base.pointSizeF() - step));
    shortcut_label_->setFont(sc);
}

void ActionsPopupItem::set_title(const QString& title) {
    if (title_label_ != nullptr) {
        title_label_->setText(title);
    }
}

void ActionsPopupItem::apply_row_colors(bool night) {
    night_ = night;
    const char* title = night ? "#eceff4" : "#1d1d1f";
    const char* sc = night ? "#8a93a8" : "#5c5c63";
    title_label_->setStyleSheet(
        QStringLiteral("QLabel#ActionsPopupItemTitle { color: %1; background: transparent; }")
            .arg(QLatin1String(title)));
    shortcut_label_->setStyleSheet(
        QStringLiteral("QLabel#ActionsPopupItemShortcut { color: %1; background: transparent; }")
            .arg(QLatin1String(sc)));
    update();
}

void ActionsPopupItem::mouseReleaseEvent(QMouseEvent* event) {
    if (event->button() == Qt::LeftButton && isEnabled() && rect().contains(event->pos())) {
        emit clicked();
        event->accept();
        return;
    }
    QFrame::mouseReleaseEvent(event);
}

void ActionsPopupItem::enterEvent(QEnterEvent* event) {
    if (host_ != nullptr) {
        host_->cancel_keyboard_highlight();
    }
    hover_ = true;
    update();
    QFrame::enterEvent(event);
}

void ActionsPopupItem::leaveEvent(QEvent* event) {
    hover_ = false;
    update();
    QFrame::leaveEvent(event);
}

void ActionsPopupItem::paintEvent(QPaintEvent*) {
    if (!hover_ || !isEnabled()) {
        return;
    }
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing, true);
    painter.setPen(Qt::NoPen);
    painter.setBrush(night_ ? QColor(255, 255, 255, 26) : QColor(0xe5, 0xea, 0xf2));
    painter.drawRoundedRect(QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5), 10.0, 10.0);
}

ActionsPopup::ActionsPopup(QWidget* parent) : QFrame(parent) {
    setObjectName("ActionsPopupWindow");
    Qt::WindowFlags flags = Qt::Popup | Qt::FramelessWindowHint;
#ifdef Q_OS_WIN
    flags |= Qt::NoDropShadowWindowHint;
#endif
    setWindowFlags(flags);
    setAttribute(Qt::WA_TranslucentBackground, true);
    setMinimumWidth(236);
    setFocusPolicy(Qt::StrongFocus);

    auto* root = new QVBoxLayout(this);
    root->setContentsMargins(0, 0, 0, 0);
    root->setSpacing(0);
    surface_ = new QFrame(this);
    surface_->setObjectName("ActionsPopupSurface");
    surface_->setAttribute(Qt::WA_TranslucentBackground, true);
    surface_->setFocusPolicy(Qt::NoFocus);
    root->addWidget(surface_);
    surface_layout_ = new QVBoxLayout(surface_);
    surface_layout_->setContentsMargins(8, 6, 8, 6);
    surface_layout_->setSpacing(1);
    apply_theme();
}

void ActionsPopup::apply_theme() {
    if (night_) {
        popup_bg_ = QColor(34, 37, 45, 244);
        popup_border_ = QColor(58, 62, 74);
    } else {
        popup_bg_ = QColor(246, 247, 250);
        popup_border_ = QColor(208, 211, 218);
    }
    setStyleSheet(QStringLiteral(
        "#ActionsPopupWindow { background: transparent; border: none; }"
        "#ActionsPopupSurface { background: transparent; border: none; }"
        "QFrame#ActionsPopupItem { background: transparent; border: none; }"
        "QFrame#ActionsPopupSeparator {"
        "  background: %1; max-height: 1px; min-height: 1px; border: none; margin: 3px 6px;"
        "}")
                      .arg(night_ ? QStringLiteral("#343a46") : QStringLiteral("#d6dce7")));
    refresh_row_colors();
    update();
}

void ActionsPopup::refresh_row_colors() {
    for (int i = 0; i < surface_layout_->count(); ++i) {
        auto* item = qobject_cast<ActionsPopupItem*>(surface_layout_->itemAt(i)->widget());
        if (item != nullptr) {
            item->apply_row_colors(night_);
        }
    }
}

void ActionsPopup::clear_actions() {
    while (surface_layout_->count() > 0) {
        QLayoutItem* item = surface_layout_->takeAt(0);
        if (item->widget() != nullptr) {
            item->widget()->deleteLater();
        }
        delete item;
    }
}

ActionsPopupItem* ActionsPopup::add_action(const QString& title, const QString& shortcut,
                                           const std::function<void()>& callback,
                                           const QString& tooltip) {
    auto* item = new ActionsPopupItem(title, shortcut, tooltip, surface_);
    item->set_host(this);
    item->apply_row_colors(night_);
    connect(item, &ActionsPopupItem::clicked, this, [this, callback] {
        hide();
        if (callback) {
            QTimer::singleShot(0, this, [callback] { callback(); });
        }
    });
    surface_layout_->addWidget(item);
    return item;
}

void ActionsPopup::add_separator() {
    auto* sep = new QFrame(surface_);
    sep->setObjectName("ActionsPopupSeparator");
    sep->setFrameShape(QFrame::NoFrame);
    sep->setFixedHeight(1);
    surface_layout_->addWidget(sep);
}

void ActionsPopup::show_below(QWidget* anchor) {
    adjustSize();
    const int popup_w = width();
    const int popup_h = height();
    const int x_local = std::max(0, anchor->width() - popup_w);
    QPoint pos = anchor->mapToGlobal(QPoint(x_local, anchor->height() + 6));
    const QPoint above = anchor->mapToGlobal(QPoint(x_local, -popup_h - 6));
    if (QScreen* screen = QApplication::screenAt(anchor->mapToGlobal(anchor->rect().center()))) {
        const QRect avail = screen->availableGeometry();
        if (pos.y() > avail.bottom() - popup_h + 1 && above.y() >= avail.top()) {
            pos = above;
        }
        pos.setX(std::clamp(pos.x(), avail.left(), avail.right() - popup_w + 1));
        pos.setY(std::clamp(pos.y(), avail.top(), avail.bottom() - popup_h + 1));
    }
    move(pos);
    show();
    QTimer::singleShot(0, this, [this] { setFocus(Qt::PopupFocusReason); });
}

void ActionsPopup::show_at(const QPoint& global_pos) {
    adjustSize();
    QPoint pos = global_pos;
    if (QScreen* screen = QApplication::screenAt(pos)) {
        const QRect avail = screen->availableGeometry();
        pos.setX(std::clamp(pos.x(), avail.left(), avail.right() - width() + 1));
        pos.setY(std::clamp(pos.y(), avail.top(), avail.bottom() - height() + 1));
    }
    move(pos);
    show();
    QTimer::singleShot(0, this, [this] { setFocus(Qt::PopupFocusReason); });
}

void ActionsPopup::set_night(bool night) {
    night_ = night;
    apply_theme();
}

void ActionsPopup::cancel_keyboard_highlight() {}

void ActionsPopup::paintEvent(QPaintEvent*) {
    paint_rounded_popup_bg(this, popup_bg_, popup_border_, kOuterRadius);
}

void ActionsPopup::showEvent(QShowEvent* event) {
    QFrame::showEvent(event);
    if (!dwm_patched_) {
        disable_dwm_rounded_frame(this);
        dwm_patched_ = true;
    }
}

void ActionsPopup::hideEvent(QHideEvent* event) {
    QFrame::hideEvent(event);
}

void ActionsPopup::resizeEvent(QResizeEvent* event) {
    QFrame::resizeEvent(event);
}

}  // namespace i2pchat::gui
