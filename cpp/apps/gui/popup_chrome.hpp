#pragma once

#include <QBitmap>
#include <QColor>
#include <QPainter>
#include <QPainterPath>
#include <QPalette>
#include <QPen>
#include <QRegion>
#include <QWidget>

#ifdef Q_OS_WIN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

namespace i2pchat::gui {

inline void prepare_translucent_popup(QWidget* widget) {
    if (widget == nullptr) {
        return;
    }
    widget->setAttribute(Qt::WA_TranslucentBackground, true);
    widget->setAttribute(Qt::WA_NoSystemBackground, true);
    widget->setAutoFillBackground(false);
}

inline void paint_rounded_popup_bg(QWidget* widget, const QColor& bg, const QColor& border,
                                   qreal radius) {
    QPainter painter(widget);
    painter.setRenderHint(QPainter::Antialiasing, true);
    painter.setCompositionMode(QPainter::CompositionMode_Source);
    painter.fillRect(widget->rect(), QColor(0, 0, 0, 0));
    painter.setCompositionMode(QPainter::CompositionMode_SourceOver);
    const QRectF r = QRectF(widget->rect()).adjusted(0.5, 0.5, -0.5, -0.5);
    painter.setPen(QPen(border, 1.0));
    painter.setBrush(bg);
    painter.drawRoundedRect(r, radius, radius);
}

// Child widgets on Windows have no alpha: "transparent" pixels become black.
// Fill the square first with the parent surface color, then draw the card.
inline void paint_rounded_popup_on_solid(QWidget* widget, const QColor& bg, const QColor& border,
                                         qreal radius, const QColor& behind) {
    QPainter painter(widget);
    painter.setRenderHint(QPainter::Antialiasing, true);
    painter.setCompositionMode(QPainter::CompositionMode_Source);
    painter.fillRect(widget->rect(), behind);
    painter.setCompositionMode(QPainter::CompositionMode_SourceOver);
    const QRectF r = QRectF(widget->rect()).adjusted(0.5, 0.5, -0.5, -0.5);
    painter.setPen(QPen(border, 1.0));
    painter.setBrush(bg);
    painter.drawRoundedRect(r, radius, radius);
}

inline void apply_rounded_popup_mask(QWidget* widget, qreal radius) {
    if (widget == nullptr) {
        return;
    }
    const int w = widget->width();
    const int h = widget->height();
    if (w < 2 || h < 2) {
        return;
    }
    QBitmap bitmap(w, h);
    bitmap.fill(Qt::color0);
    QPainter painter(&bitmap);
    painter.setPen(Qt::NoPen);
    painter.setBrush(Qt::color1);
    painter.drawRoundedRect(0, 0, w, h, radius, radius);
    widget->setMask(bitmap);
}

inline void make_widget_palette_transparent(QWidget* widget) {
    if (widget == nullptr) {
        return;
    }
    QPalette pal = widget->palette();
    pal.setColor(QPalette::Window, Qt::transparent);
    pal.setColor(QPalette::Base, Qt::transparent);
    widget->setPalette(pal);
    widget->setAutoFillBackground(false);
}

inline void disable_dwm_rounded_frame(QWidget* widget) {
#ifdef Q_OS_WIN
    if (widget == nullptr) {
        return;
    }
    const HWND hwnd = reinterpret_cast<HWND>(widget->winId());
    if (hwnd == nullptr) {
        return;
    }
    using DwmSetWindowAttributeFn = HRESULT(WINAPI*)(HWND, DWORD, LPCVOID, DWORD);
    HMODULE dwmapi = ::LoadLibraryW(L"dwmapi.dll");
    if (dwmapi == nullptr) {
        return;
    }
    auto fn = reinterpret_cast<DwmSetWindowAttributeFn>(
        ::GetProcAddress(dwmapi, "DwmSetWindowAttribute"));
    if (fn != nullptr) {
        const int pref = 1;  // DWMWCP_DONOTROUND
        fn(hwnd, 33, &pref, sizeof(pref));
    }
    ::FreeLibrary(dwmapi);
#else
    Q_UNUSED(widget);
#endif
}

}  // namespace i2pchat::gui
