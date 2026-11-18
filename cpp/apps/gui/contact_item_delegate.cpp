#include "contact_item_delegate.hpp"

#include <QPainter>
#include <QPainterPath>
#include <QPixmap>

#include "contact_list_model.hpp"

namespace i2pchat::gui {
namespace {

constexpr int kAvatarSize = 36;

QPixmap circular_avatar(const QString& path, int size, const QColor& placeholder,
                        const QString& initial) {
    QPixmap canvas(size, size);
    canvas.fill(Qt::transparent);
    QPainter painter(&canvas);
    painter.setRenderHint(QPainter::Antialiasing);
    QPainterPath clip;
    clip.addEllipse(0, 0, size, size);
    painter.setClipPath(clip);
    QPixmap source(path);
    if (!source.isNull()) {
        const QPixmap scaled =
            source.scaled(size, size, Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation);
        painter.drawPixmap(QRect(0, 0, size, size), scaled);
    } else {
        painter.fillRect(0, 0, size, size, placeholder);
        painter.setPen(Qt::white);
        QFont font = painter.font();
        font.setBold(true);
        font.setPointSizeF(size * 0.35);
        painter.setFont(font);
        painter.drawText(QRect(0, 0, size, size), Qt::AlignCenter,
                         initial.isEmpty() ? QStringLiteral("?") : initial.left(1).toUpper());
    }
    return canvas;
}

}  // namespace

ContactItemDelegate::ContactItemDelegate(QObject* parent) : QStyledItemDelegate(parent) {}

QSize ContactItemDelegate::sizeHint(const QStyleOptionViewItem& option,
                                    const QModelIndex& index) const {
    const int kind = index.data(ContactListModel::KindRole).toInt();
    if (kind == static_cast<int>(SidebarKind::Section)) {
        return {option.rect.width(), 36};
    }
    return {option.rect.width(), 52};
}

void ContactItemDelegate::paint(QPainter* painter, const QStyleOptionViewItem& option,
                                const QModelIndex& index) const {
    painter->save();
    painter->setRenderHint(QPainter::Antialiasing);
    const int kind = index.data(ContactListModel::KindRole).toInt();
    const QString title = index.data(ContactListModel::TitleRole).toString();
    const QString subtitle = index.data(ContactListModel::SubtitleRole).toString();
    const QString avatar_path = index.data(ContactListModel::AvatarPathRole).toString();
    const bool live = index.data(ContactListModel::LiveRole).toBool();
    const unsigned unread = index.data(ContactListModel::UnreadRole).toUInt();
    const bool selected = option.state.testFlag(QStyle::State_Selected) ||
                          index.data(ContactListModel::SelectedRole).toBool();

    const QColor title_color = dark_ ? QColor(245, 245, 247) : QColor(29, 29, 31);
    const QColor muted = dark_ ? QColor(159, 161, 181) : QColor(98, 104, 117);
    const QColor accent(10, 132, 255);

    if (kind == static_cast<int>(SidebarKind::Section)) {
        QFont font = option.font;
        font.setPointSizeF(std::max(10.0, font.pointSizeF() - 1.0));
        font.setBold(true);
        painter->setFont(font);
        painter->setPen(muted);
        painter->drawText(option.rect.adjusted(16, 8, -8, 0), Qt::AlignLeft | Qt::AlignTop,
                          title);
        if (!subtitle.isEmpty()) {
            QFont small = option.font;
            small.setPointSizeF(std::max(9.0, small.pointSizeF() - 2.0));
            small.setBold(false);
            painter->setFont(small);
            painter->drawText(option.rect.adjusted(16, 22, -8, -2),
                              Qt::AlignLeft | Qt::AlignTop | Qt::TextWordWrap, subtitle);
        }
        painter->restore();
        return;
    }

    QRect bubble = option.rect.adjusted(8, 3, -6, -3);
    if (selected) {
        QPainterPath path;
        path.addRoundedRect(bubble, 8, 8);
        const QColor fill = kind == static_cast<int>(SidebarKind::Group)
                                ? (dark_ ? QColor(124, 58, 237, 40) : QColor(124, 58, 237, 28))
                                : (dark_ ? QColor(255, 255, 255, 28) : QColor(10, 132, 255, 28));
        painter->fillPath(path, fill);
    }

    const int avatar_y = bubble.top() + (bubble.height() - kAvatarSize) / 2;
    const QRect avatar_rect(bubble.left() + 8, avatar_y, kAvatarSize, kAvatarSize);
    const QColor placeholder =
        kind == static_cast<int>(SidebarKind::Group) ? QColor(124, 58, 237) : accent;
    painter->drawPixmap(avatar_rect,
                        circular_avatar(avatar_path, kAvatarSize, placeholder, title));

    const int text_left = avatar_rect.right() + 10;
    QFont title_font = option.font;
    title_font.setBold(true);
    painter->setFont(title_font);
    painter->setPen(title_color);
    QRect title_rect(text_left, bubble.top() + 6,
                     bubble.right() - text_left - (live || unread ? 28 : 8), 18);
    painter->drawText(title_rect, Qt::AlignLeft | Qt::AlignVCenter | Qt::TextSingleLine, title);

    QFont sub_font = option.font;
    sub_font.setPointSizeF(std::max(9.0, sub_font.pointSizeF() - 1.5));
    sub_font.setBold(false);
    painter->setFont(sub_font);
    painter->setPen(muted);
    painter->drawText(QRect(text_left, bubble.top() + 26, bubble.right() - text_left - 10, 16),
                      Qt::AlignLeft | Qt::AlignVCenter | Qt::TextSingleLine, subtitle);

    if (unread > 0) {
        const QString badge = unread > 99 ? QStringLiteral("99+") : QString::number(unread);
        const QRect badge_rect(bubble.right() - 28, bubble.top() + 8, 22, 16);
        QPainterPath path;
        path.addRoundedRect(badge_rect, 8, 8);
        painter->fillPath(path, accent);
        painter->setPen(Qt::white);
        QFont badge_font = option.font;
        badge_font.setPointSizeF(9);
        badge_font.setBold(true);
        painter->setFont(badge_font);
        painter->drawText(badge_rect, Qt::AlignCenter, badge);
    } else if (live) {
        painter->setBrush(QColor(48, 209, 88));
        painter->setPen(Qt::NoPen);
        painter->drawEllipse(QPoint(bubble.right() - 14, bubble.top() + 16), 4, 4);
    }
    painter->restore();
}

}  // namespace i2pchat::gui
