#include "rounded_scrollbar.hpp"

#include <algorithm>
#include <cmath>

#include <QCoreApplication>
#include <QMouseEvent>
#include <QPainter>
#include <QScrollBar>
#include <QWheelEvent>

namespace i2pchat::gui {

RoundedVerticalScrollbar::RoundedVerticalScrollbar(QScrollBar* linked, QWidget* parent)
    : QWidget(parent), sb_(linked) {
    setFixedWidth(6);
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::NoFocus);
    setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Expanding);
    if (sb_ != nullptr) {
        connect(sb_, &QScrollBar::valueChanged, this, [this](int) { update(); });
        connect(sb_, &QScrollBar::rangeChanged, this, [this](int, int) { update(); });
    }
}

void RoundedVerticalScrollbar::set_colors(const QColor& thumb, const QColor& track) {
    thumb_ = thumb;
    track_ = track;
    update();
}

QRectF RoundedVerticalScrollbar::compute_thumb() const {
    const qreal track_h = std::max(0, height());
    if (track_h <= 0 || sb_ == nullptr) {
        return QRectF(0, 0, width(), 0);
    }
    const int sb_min = sb_->minimum();
    const int sb_max = sb_->maximum();
    const int sb_range = std::max(0, sb_max - sb_min);
    const int page = std::max(0, sb_->pageStep());
    const int total = sb_range + page;
    qreal visible_ratio = total > 0 ? static_cast<qreal>(page) / static_cast<qreal>(total) : 1.0;
    visible_ratio = std::clamp(visible_ratio, 0.05, 1.0);
    const qreal thumb_h = std::clamp(track_h * visible_ratio, 16.0, track_h);
    qreal progress = 0.0;
    if (sb_range > 0) {
        progress = std::clamp(static_cast<qreal>(sb_->value() - sb_min) / static_cast<qreal>(sb_range),
                              0.0, 1.0);
    }
    const qreal travel = std::max(0.0, track_h - thumb_h);
    return QRectF(0.0, travel * progress, width(), thumb_h);
}

void RoundedVerticalScrollbar::set_value_from_thumb_y(qreal thumb_y, qreal thumb_h) {
    if (sb_ == nullptr) {
        return;
    }
    const int sb_min = sb_->minimum();
    const int sb_max = sb_->maximum();
    const int sb_range = std::max(0, sb_max - sb_min);
    if (sb_range <= 0) {
        return;
    }
    const qreal travel = std::max(0.0, static_cast<qreal>(std::max(1, height())) - thumb_h);
    if (travel <= 0.0) {
        sb_->setValue(sb_min);
        return;
    }
    const qreal progress = std::clamp(thumb_y / travel, 0.0, 1.0);
    sb_->setValue(static_cast<int>(std::lround(sb_min + progress * static_cast<qreal>(sb_range))));
}

void RoundedVerticalScrollbar::paintEvent(QPaintEvent*) {
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing, true);
    const qreal radius = static_cast<qreal>(width()) / 2.0;
    painter.setPen(Qt::NoPen);
    if (track_.alpha() > 0) {
        painter.setBrush(track_);
        painter.drawRoundedRect(QRectF(rect()), radius, radius);
    }
    painter.setBrush(thumb_);
    painter.drawRoundedRect(compute_thumb(), radius, radius);
}

void RoundedVerticalScrollbar::mousePressEvent(QMouseEvent* event) {
    const QRectF thumb = compute_thumb();
    if (thumb.height() <= 0) {
        return;
    }
    dragging_ = true;
    if (thumb.contains(event->position())) {
        drag_offset_y_ = static_cast<int>(event->position().y() - thumb.y());
    } else {
        drag_offset_y_ = static_cast<int>(thumb.height() / 2.0);
    }
    qreal y = event->position().y() - drag_offset_y_;
    y = std::clamp(y, 0.0, static_cast<qreal>(height()) - thumb.height());
    set_value_from_thumb_y(y, thumb.height());
    event->accept();
}

void RoundedVerticalScrollbar::mouseMoveEvent(QMouseEvent* event) {
    if (!dragging_) {
        return;
    }
    const QRectF thumb = compute_thumb();
    if (thumb.height() <= 0) {
        return;
    }
    qreal y = event->position().y() - drag_offset_y_;
    y = std::clamp(y, 0.0, static_cast<qreal>(height()) - thumb.height());
    set_value_from_thumb_y(y, thumb.height());
    event->accept();
}

void RoundedVerticalScrollbar::mouseReleaseEvent(QMouseEvent* event) {
    dragging_ = false;
    event->accept();
}

void RoundedVerticalScrollbar::wheelEvent(QWheelEvent* event) {
    if (sb_ != nullptr) {
        QCoreApplication::sendEvent(sb_, event);
        return;
    }
    QWidget::wheelEvent(event);
}

}  // namespace i2pchat::gui
