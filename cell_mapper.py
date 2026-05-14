#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
cell_mapper.py

Что делает:
- GUI для привязки товара к номеру ячейки: выбираешь ячейку -> сканируешь ШК.
- ШК можно вводить как "30497065230799", так и "=30497065230799=" — знаки = не обязательны.
- Добавление: по Enter или при скане в формате =цифры= (авто-Enter).
- Конвертирует ШК в артикул: первые 8 цифр -> "XXX.XXX.XX"
- Сохраняет результат в CSV или XLSX: cell, barcode, article
- НОВОЕ: если ШК уже есть в таблице -> предлагает выбрать, где он лежит:
  оставить в старой ячейке или переложить в текущую (обновить ячейку у существующей записи).

Установка:
    python3 -m pip install -U pyside6 pandas openpyxl
Запуск:
    python3 cell_mapper.py
"""

import re
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Optional, List, Dict

import pandas as pd
from PySide6 import QtCore, QtGui, QtWidgets


# Минимум 8 цифр для артикула. = в начале/конце не обязательны.
SCAN_RE = re.compile(r"=(\d{8,})=")   # авто-добавление при формате сканера =...=
DIGITS_RE = re.compile(r"(\d{8,})")   # извлечение цифр из любого ввода (для Enter)


def barcode_to_article(raw_digits: str) -> str:
    """
    Берём первые 8 цифр и форматируем XXX.XXX.XX
    """
    core = raw_digits[:8]
    if len(core) < 8 or not core.isdigit():
        raise ValueError("Недостаточно цифр для артикула (нужно минимум 8).")
    return f"{core[0:3]}.{core[3:6]}.{core[6:8]}"


@dataclass
class Row:
    cell: int
    barcode: str   # чистые цифры без '='
    article: str


@dataclass
class InventoryRow:
    barcode: str   # чистые цифры без '='
    article: str
    qty: int


class MainWindow(QtWidgets.QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Привязка товара к ячейке — ИКЕЯ")
        self.resize(1020, 680)

        self.rows: List[Row] = []
        self.save_path: Optional[Path] = None

        # --- Инвентарь (вкладка) ---
        self.inv_rows: List[InventoryRow] = []
        self.inv_save_path: Optional[Path] = None

        # Таймер авто-Enter для ШК без "=": после паузы в вводе обрабатываем как Enter
        self._auto_enter_timer = QtCore.QTimer(self)
        self._auto_enter_timer.setSingleShot(True)
        self._auto_enter_timer.setInterval(220)
        self._auto_enter_timer.timeout.connect(self._on_auto_enter_timeout)

        # Таймер авто-Enter для инвентаря
        self._inv_auto_enter_timer = QtCore.QTimer(self)
        self._inv_auto_enter_timer.setSingleShot(True)
        self._inv_auto_enter_timer.setInterval(220)
        self._inv_auto_enter_timer.timeout.connect(self._on_inv_auto_enter_timeout)

        # Защита от повторного открытия диалога при “дребезге” ввода сканером
        self._inv_prompting_qty = False

        # --- UI ---
        central = QtWidgets.QWidget()
        self.setCentralWidget(central)
        root = QtWidgets.QVBoxLayout(central)
        root.setContentsMargins(20, 20, 20, 20)
        root.setSpacing(14)

        self.tabs = QtWidgets.QTabWidget()
        root.addWidget(self.tabs, 1)

        # ---------------- Tab 1: Ячейки ----------------
        cells_tab = QtWidgets.QWidget()
        self.tabs.addTab(cells_tab, "Ячейки")
        cells_layout = QtWidgets.QVBoxLayout(cells_tab)
        cells_layout.setContentsMargins(0, 0, 0, 0)
        cells_layout.setSpacing(14)

        # Верхняя панель
        top = QtWidgets.QHBoxLayout()
        cells_layout.addLayout(top)

        self.cell_spin = QtWidgets.QSpinBox()
        self.cell_spin.setRange(1, 1_000_000)
        self.cell_spin.setValue(1)
        self.cell_spin.setPrefix("Ячейка: ")
        self.cell_spin.setFixedHeight(36)
        top.addWidget(self.cell_spin)

        self.btn_prev = QtWidgets.QPushButton("← Пред")
        self.btn_prev.setFixedHeight(36)
        self.btn_next = QtWidgets.QPushButton("След →")
        self.btn_next.setFixedHeight(36)
        top.addWidget(self.btn_prev)
        top.addWidget(self.btn_next)

        top.addSpacing(12)

        self.path_label = QtWidgets.QLabel("Файл не выбран")
        self.path_label.setTextInteractionFlags(QtCore.Qt.TextSelectableByMouse)
        top.addWidget(self.path_label, 1)

        self.btn_pick = QtWidgets.QPushButton("Выбрать файл…")
        self.btn_pick.setFixedHeight(36)
        top.addWidget(self.btn_pick)

        # Скан-поле
        scan_row = QtWidgets.QHBoxLayout()
        cells_layout.addLayout(scan_row)

        self.scan_edit = QtWidgets.QLineEdit()
        self.scan_edit.setPlaceholderText("Сканируй ШК или введи цифры и нажми Enter")
        self.scan_edit.setFixedHeight(52)
        font = self.scan_edit.font()
        font.setPointSize(font.pointSize() + 3)
        self.scan_edit.setFont(font)
        scan_row.addWidget(self.scan_edit, 1)

        self.auto_advance = QtWidgets.QCheckBox("После добавления: +1 к ячейке")
        self.auto_advance.setChecked(True)
        scan_row.addWidget(self.auto_advance)

        self.ask_on_duplicate = QtWidgets.QCheckBox("Спрашивать при дубле ШК")
        self.ask_on_duplicate.setChecked(True)
        scan_row.addWidget(self.ask_on_duplicate)

        self.auto_enter_no_equals = QtWidgets.QCheckBox("Авто-Enter для ШК без =")
        self.auto_enter_no_equals.setChecked(True)
        self.auto_enter_no_equals.setToolTip("После паузы в вводе цифр (без =) автоматически добавить запись")
        scan_row.addWidget(self.auto_enter_no_equals)

        # Таблица
        self.table = QtWidgets.QTableWidget(0, 3)
        self.table.setHorizontalHeaderLabels(["Ячейка", "ШК (цифры)", "Артикул"])
        self.table.horizontalHeader().setStretchLastSection(True)
        self.table.setSelectionBehavior(QtWidgets.QAbstractItemView.SelectRows)
        self.table.setEditTriggers(QtWidgets.QAbstractItemView.NoEditTriggers)
        cells_layout.addWidget(self.table, 1)

        # Нижняя панель
        bottom = QtWidgets.QHBoxLayout()
        cells_layout.addLayout(bottom)

        self.btn_del = QtWidgets.QPushButton("Удалить строку")
        self.btn_clear = QtWidgets.QPushButton("Очистить всё")
        self.btn_csv = QtWidgets.QPushButton("Сохранить CSV")
        self.btn_xlsx = QtWidgets.QPushButton("Сохранить XLSX")

        for b in (self.btn_del, self.btn_clear, self.btn_csv, self.btn_xlsx):
            b.setFixedHeight(38)

        bottom.addWidget(self.btn_del)
        bottom.addWidget(self.btn_clear)
        bottom.addStretch(1)
        bottom.addWidget(self.btn_csv)
        bottom.addWidget(self.btn_xlsx)

        # ---------------- Tab 2: Инвентарь ----------------
        inv_tab = QtWidgets.QWidget()
        self.tabs.addTab(inv_tab, "Инвентарь")
        inv_layout = QtWidgets.QVBoxLayout(inv_tab)
        inv_layout.setContentsMargins(0, 0, 0, 0)
        inv_layout.setSpacing(14)

        inv_top = QtWidgets.QHBoxLayout()
        inv_layout.addLayout(inv_top)

        inv_top.addSpacing(12)

        self.inv_path_label = QtWidgets.QLabel("Файл не выбран")
        self.inv_path_label.setTextInteractionFlags(QtCore.Qt.TextSelectableByMouse)
        inv_top.addWidget(self.inv_path_label, 1)

        self.inv_btn_pick = QtWidgets.QPushButton("Выбрать файл…")
        self.inv_btn_pick.setFixedHeight(36)
        inv_top.addWidget(self.inv_btn_pick)

        # Скан-поле
        inv_scan_row = QtWidgets.QHBoxLayout()
        inv_layout.addLayout(inv_scan_row)

        self.inv_scan_edit = QtWidgets.QLineEdit()
        self.inv_scan_edit.setPlaceholderText("Сканируй ШК или введи цифры и нажми Enter")
        self.inv_scan_edit.setFixedHeight(52)
        font = self.inv_scan_edit.font()
        font.setPointSize(font.pointSize() + 3)
        self.inv_scan_edit.setFont(font)
        inv_scan_row.addWidget(self.inv_scan_edit, 1)

        # При повторном сканировании одного и того же товара нужно прибавлять.
        self.inv_additive = QtWidgets.QCheckBox("Прибавлять при повторном сканировании")
        self.inv_additive.setChecked(True)
        self.inv_additive.setEnabled(False)
        inv_scan_row.addWidget(self.inv_additive)

        self.inv_auto_enter_no_equals = QtWidgets.QCheckBox("Авто-Enter для ШК без =")
        self.inv_auto_enter_no_equals.setChecked(True)
        self.inv_auto_enter_no_equals.setToolTip("После паузы в вводе цифр (без =) автоматически добавить запись")
        inv_scan_row.addWidget(self.inv_auto_enter_no_equals)

        # Таблица
        self.inv_table = QtWidgets.QTableWidget(0, 3)
        self.inv_table.setHorizontalHeaderLabels(["ШК (цифры)", "Артикул", "Кол-во"])
        self.inv_table.horizontalHeader().setStretchLastSection(True)
        self.inv_table.setSelectionBehavior(QtWidgets.QAbstractItemView.SelectRows)
        self.inv_table.setEditTriggers(QtWidgets.QAbstractItemView.NoEditTriggers)
        inv_layout.addWidget(self.inv_table, 1)

        # Нижняя панель
        inv_bottom = QtWidgets.QHBoxLayout()
        inv_layout.addLayout(inv_bottom)

        self.inv_btn_del = QtWidgets.QPushButton("Удалить строку")
        self.inv_btn_clear = QtWidgets.QPushButton("Очистить всё")
        self.inv_btn_csv = QtWidgets.QPushButton("Сохранить CSV")
        self.inv_btn_xlsx = QtWidgets.QPushButton("Сохранить XLSX")

        for b in (self.inv_btn_del, self.inv_btn_clear, self.inv_btn_csv, self.inv_btn_xlsx):
            b.setFixedHeight(38)

        inv_bottom.addWidget(self.inv_btn_del)
        inv_bottom.addWidget(self.inv_btn_clear)
        inv_bottom.addStretch(1)
        inv_bottom.addWidget(self.inv_btn_csv)
        inv_bottom.addWidget(self.inv_btn_xlsx)

        # Подсказки/статус (общий)
        self.status = QtWidgets.QLabel("")
        self.status.setMinimumHeight(28)
        root.addWidget(self.status)

        # --- Signals ---
        self.btn_prev.clicked.connect(self.on_prev)
        self.btn_next.clicked.connect(self.on_next)
        self.btn_pick.clicked.connect(self.pick_path)

        self.scan_edit.textChanged.connect(self.on_scan_text_changed)
        self.scan_edit.returnPressed.connect(self.on_scan_enter)

        self.btn_del.clicked.connect(self.delete_selected)
        self.btn_clear.clicked.connect(self.clear_all)
        self.btn_csv.clicked.connect(lambda: self.save("csv"))
        self.btn_xlsx.clicked.connect(lambda: self.save("xlsx"))

        self.inv_btn_pick.clicked.connect(self.pick_inv_path)
        self.inv_scan_edit.textChanged.connect(self.on_inv_scan_text_changed)
        self.inv_scan_edit.returnPressed.connect(self.on_inv_scan_enter)

        self.inv_btn_del.clicked.connect(self.delete_selected_inventory)
        self.inv_btn_clear.clicked.connect(self.clear_all_inventory)
        self.inv_btn_csv.clicked.connect(lambda: self.save_inventory("csv"))
        self.inv_btn_xlsx.clicked.connect(lambda: self.save_inventory("xlsx"))

        self.tabs.currentChanged.connect(self.on_tab_changed)

        # Фокус сразу на скан в активной вкладке
        QtCore.QTimer.singleShot(0, lambda: self.on_tab_changed(self.tabs.currentIndex()))

        self.apply_dark_theme()

    # ---------------- UI helpers ----------------

    def apply_dark_theme(self):
        self.btn_next.setObjectName("primary")
        self.btn_del.setObjectName("danger")
        self.inv_btn_xlsx.setObjectName("primary")
        self.inv_btn_del.setObjectName("danger")
        # Тема: тёмный фон, жёлто-синий акцент в духе ИКЕЯ, мягкие скругления
        self.setStyleSheet(
            """
            QWidget { font-size: 13px; }
            QMainWindow { background: #1a1a1d; }
            QLabel { color: #e4e4e7; }
            QLineEdit {
                background: #252529; color: #fafafa;
                border: 2px solid #3f3f46; border-radius: 12px;
                padding: 10px 14px; selection-background-color: #facc15;
            }
            QLineEdit:focus { border-color: #facc15; }
            QSpinBox {
                background: #252529; color: #fafafa;
                border: 2px solid #3f3f46; border-radius: 10px;
                padding: 8px 12px; min-width: 100px;
            }
            QSpinBox:focus { border-color: #38bdf8; }
            QPushButton {
                background: #27272a; color: #fafafa;
                border: 1px solid #3f3f46; border-radius: 10px;
                padding: 10px 16px; font-weight: 500;
            }
            QPushButton:hover { background: #3f3f46; border-color: #52525b; }
            QPushButton:pressed { background: #18181b; }
            QPushButton#primary {
                background: #facc15; color: #18181b; border-color: #eab308;
            }
            QPushButton#primary:hover { background: #fde047; }
            QPushButton#danger { color: #f87171; }
            QPushButton#danger:hover { background: #7f1d1d; color: #fecaca; }
            QTableWidget {
                background: #18181b; color: #fafafa;
                border: 1px solid #27272a; border-radius: 12px;
                gridline-color: #27272a;
            }
            QHeaderView::section {
                background: #27272a; color: #a1a1aa;
                border: none; border-bottom: 2px solid #facc15;
                padding: 10px 8px; font-weight: 600;
            }
            QCheckBox { color: #e4e4e7; spacing: 8px; }
            QCheckBox::indicator { width: 18px; height: 18px; border-radius: 4px; border: 2px solid #52525b; background: #27272a; }
            QCheckBox::indicator:checked { background: #38bdf8; border-color: #38bdf8; }
            """
        )
        self.scan_edit.setAttribute(QtCore.Qt.WA_MacShowFocusRect, False)
        self.inv_scan_edit.setAttribute(QtCore.Qt.WA_MacShowFocusRect, False)

    def set_status(self, msg: str, error: bool = False):
        self.status.setText(msg)
        self.status.setStyleSheet("color: #ff6b6b;" if error else "color: #b6ffb6;")

    def _clear_scan(self):
        self._auto_enter_timer.stop()
        self.scan_edit.blockSignals(True)
        self.scan_edit.clear()
        self.scan_edit.blockSignals(False)
        self.scan_edit.setFocus()

    def on_tab_changed(self, index: int):
        # Фокус нужен, чтобы сканер продолжал “печатать” в активное поле
        if index == 0:
            self.scan_edit.setFocus()
        else:
            self.inv_scan_edit.setFocus()

    def _clear_inv_scan(self):
        self._inv_auto_enter_timer.stop()
        self.inv_scan_edit.blockSignals(True)
        self.inv_scan_edit.clear()
        self.inv_scan_edit.blockSignals(False)
        self.inv_scan_edit.setFocus()

    def pick_inv_path(self):
        suggested = self.inv_save_path or (Path.home() / "Desktop" / "inventory.xlsx")
        fn, _ = QtWidgets.QFileDialog.getSaveFileName(
            self,
            "Выбери файл для сохранения",
            str(suggested),
            "Excel (*.xlsx);;CSV (*.csv)"
        )
        if not fn:
            return
        self.inv_save_path = Path(fn)
        self.inv_path_label.setText(str(self.inv_save_path))

    # ---------------- logic ----------------

    def on_prev(self):
        v = self.cell_spin.value()
        if v > 1:
            self.cell_spin.setValue(v - 1)
        self.scan_edit.setFocus()

    def on_next(self):
        self.cell_spin.setValue(self.cell_spin.value() + 1)
        self.scan_edit.setFocus()

    def pick_path(self):
        suggested = self.save_path or (Path.home() / "Desktop" / "cells_map.xlsx")
        fn, _ = QtWidgets.QFileDialog.getSaveFileName(
            self,
            "Выбери файл для сохранения",
            str(suggested),
            "Excel (*.xlsx);;CSV (*.csv)"
        )
        if not fn:
            return
        self.save_path = Path(fn)
        self.path_label.setText(str(self.save_path))

    def find_barcode_rows(self, barcode_digits: str) -> List[int]:
        """Вернуть список индексов строк self.rows с данным barcode."""
        idxs = []
        for i, r in enumerate(self.rows):
            if r.barcode == barcode_digits:
                idxs.append(i)
        return idxs

    def prompt_duplicate_choice(self, barcode: str, article: str, existing_cells: List[int], current_cell: int) -> str:
        """
        Возвращает action:
          - "keep"  : оставить в старой ячейке (ничего не менять)
          - "move"  : переложить в текущую ячейку (обновить ячейку у существующих записей)
          - "cancel": отмена (ничего не менять)
        """
        msg = QtWidgets.QMessageBox(self)
        msg.setIcon(QtWidgets.QMessageBox.Warning)
        msg.setWindowTitle("Дубль ШК")
        # Контрастный текст в диалоге (не серый на сером)
        msg.setStyleSheet(
            """
            QMessageBox { background: #252529; }
            QMessageBox QLabel { color: #f4f4f5; font-size: 13px; }
            QMessageBox QPushButton {
                background: #3f3f46; color: #fafafa; border: 1px solid #52525b;
                border-radius: 8px; padding: 8px 16px; min-width: 80px;
            }
            QMessageBox QPushButton:hover { background: #52525b; }
            """
        )
        ex = ", ".join(map(str, sorted(set(existing_cells))))
        msg.setText(f"ШК уже есть в таблице.\n\nШК: {barcode}\nАртикул: {article}\nСейчас лежит в ячейке(ах): {ex}\nТекущая ячейка: {current_cell}")
        msg.setInformativeText("Куда считаем, что он лежит?")

        btn_keep = msg.addButton(f"Оставить в старой ({ex})", QtWidgets.QMessageBox.AcceptRole)
        btn_move = msg.addButton(f"Переложить в текущую ({current_cell})", QtWidgets.QMessageBox.DestructiveRole)
        btn_cancel = msg.addButton("Отмена", QtWidgets.QMessageBox.RejectRole)

        msg.setDefaultButton(btn_keep)
        msg.exec()

        clicked = msg.clickedButton()
        if clicked == btn_keep:
            return "keep"
        if clicked == btn_move:
            return "move"
        return "cancel"

    def move_barcode_to_cell(self, barcode_digits: str, new_cell: int):
        """Обновить ячейку у всех строк с этим ШК и обновить таблицу."""
        idxs = self.find_barcode_rows(barcode_digits)
        for i in idxs:
            self.rows[i].cell = new_cell
            item = self.table.item(i, 0)
            if item:
                item.setText(str(new_cell))

        # подсветим изменённые строки
        for i in idxs:
            for c in range(3):
                it = self.table.item(i, c)
                if it:
                    it.setBackground(QtGui.QColor(60, 60, 20))  # тёплая подсветка

    def on_scan_text_changed(self, text: str):
        """Авто-добавление: =цифры= сразу; только цифры — по таймеру (авто-Enter без =)."""
        m = SCAN_RE.search(text)
        if m:
            self._auto_enter_timer.stop()
            self._process_barcode(m.group(1))
            return
        # ШК без "=": если в поле только цифры 8+, через паузу авто-добавить
        if self.auto_enter_no_equals.isChecked():
            t = text.strip()
            if t.isdigit() and len(t) >= 8:
                self._auto_enter_timer.start()
                return
        self._auto_enter_timer.stop()

    def _on_auto_enter_timeout(self):
        """Срабатывает после паузы: если в поле только цифры 8+, обработать как Enter."""
        text = self.scan_edit.text().strip()
        if not text.isdigit() or len(text) < 8:
            return
        self._process_barcode(text)

    def on_scan_enter(self):
        """По Enter — взять из поля любые 8+ цифр и добавить запись (без обязательных =)."""
        text = self.scan_edit.text().strip()
        m = DIGITS_RE.search(text)
        if m:
            self._process_barcode(m.group(1))

    def _process_barcode(self, digits: str):
        """Общая логика: артикул, дубликаты, добавление строки, сдвиг ячейки."""
        try:
            article = barcode_to_article(digits)
        except Exception as e:
            self.set_status(f"Ошибка: {e}", error=True)
            self._clear_scan()
            return

        current_cell = int(self.cell_spin.value())
        existing_idxs = self.find_barcode_rows(digits)

        if existing_idxs and self.ask_on_duplicate.isChecked():
            existing_cells = [self.rows[i].cell for i in existing_idxs]
            if all(c == current_cell for c in existing_cells):
                self.set_status(f"Уже есть: ячейка {current_cell} | {digits} | {article}", error=False)
                self._clear_scan()
                return

            action = self.prompt_duplicate_choice(digits, article, existing_cells, current_cell)
            if action == "keep":
                self.set_status(f"Оставили в старой(ых): {sorted(set(existing_cells))} | {digits} | {article}")
                self._clear_scan()
                return
            if action == "move":
                self.move_barcode_to_cell(digits, current_cell)
                self.set_status(f"Переложили в ячейку {current_cell} | {digits} | {article}")
                self._clear_scan()
                if self.auto_advance.isChecked():
                    self.cell_spin.setValue(current_cell + 1)
                return

            self.set_status("Отменено", error=True)
            self._clear_scan()
            return

        self.add_row(Row(cell=current_cell, barcode=digits, article=article))
        self.set_status(f"Добавлено: ячейка {current_cell} | {digits} | {article}")
        self._clear_scan()
        if self.auto_advance.isChecked():
            self.cell_spin.setValue(current_cell + 1)

    def add_row(self, row: Row):
        self.rows.append(row)
        r = self.table.rowCount()
        self.table.insertRow(r)
        self.table.setItem(r, 0, QtWidgets.QTableWidgetItem(str(row.cell)))
        self.table.setItem(r, 1, QtWidgets.QTableWidgetItem(row.barcode))
        self.table.setItem(r, 2, QtWidgets.QTableWidgetItem(row.article))
        self.table.scrollToBottom()

    def delete_selected(self):
        sel = self.table.selectionModel().selectedRows()
        if not sel:
            self.set_status("Нечего удалять: строка не выбрана", error=True)
            return
        for idx in sorted((s.row() for s in sel), reverse=True):
            self.table.removeRow(idx)
            del self.rows[idx]
        self.set_status("Удалено")

    def clear_all(self):
        self.table.setRowCount(0)
        self.rows.clear()
        self.set_status("Очищено")

    def default_path_for(self, ext: str) -> Path:
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        return Path.home() / "Desktop" / f"cells_map_{ts}.{ext}"

    def save(self, kind: str):
        if not self.rows:
            self.set_status("Пусто: нечего сохранять", error=True)
            return

        if self.save_path is None:
            self.save_path = self.default_path_for("xlsx" if kind == "xlsx" else "csv")
            self.path_label.setText(str(self.save_path))

        out = self.save_path
        if kind == "csv" and out.suffix.lower() != ".csv":
            out = out.with_suffix(".csv")
        if kind == "xlsx" and out.suffix.lower() != ".xlsx":
            out = out.with_suffix(".xlsx")

        df = pd.DataFrame([{"cell": r.cell, "barcode": r.barcode, "article": r.article} for r in self.rows])

        try:
            if kind == "csv":
                df.to_csv(out, index=False, encoding="utf-8-sig")
            else:
                df.to_excel(out, index=False)
        except Exception as e:
            self.set_status(f"Ошибка сохранения: {e}", error=True)
            return

        self.set_status(f"Сохранено: {out}")

    # ---------------- Инвентарь ----------------

    def find_inv_row_index(self, barcode_digits: str) -> Optional[int]:
        for i, r in enumerate(self.inv_rows):
            if r.barcode == barcode_digits:
                return i
        return None

    def on_inv_scan_text_changed(self, text: str):
        """Авто-добавление для инвентаря: =цифры= сразу; только цифры — по таймеру."""
        m = SCAN_RE.search(text)
        if m:
            self._inv_auto_enter_timer.stop()
            self._process_inventory_barcode(m.group(1))
            return

        if self.inv_auto_enter_no_equals.isChecked():
            t = text.strip()
            if t.isdigit() and len(t) >= 8:
                self._inv_auto_enter_timer.start()
                return
        self._inv_auto_enter_timer.stop()

    def _on_inv_auto_enter_timeout(self):
        """Срабатывает после паузы: если в поле только цифры 8+, обработать как Enter."""
        text = self.inv_scan_edit.text().strip()
        if not text.isdigit() or len(text) < 8:
            return
        self._process_inventory_barcode(text)

    def on_inv_scan_enter(self):
        """По Enter — взять из поля любые 8+ цифр и добавить/обновить запись инвентаря."""
        text = self.inv_scan_edit.text().strip()
        m = DIGITS_RE.search(text)
        if m:
            self._process_inventory_barcode(m.group(1))

    def _ask_inventory_qty(self, digits: str, article: str) -> Optional[int]:
        """Попросить у пользователя кол-во (всплывающая "плашка")."""
        if self._inv_prompting_qty:
            return None
        title = "Инвентарь: количество"
        label = f"Введите кол-во вручную:\n\nШК: {digits}\nАртикул: {article}"
        # QInputDialog.getInt возвращает (value, ok)
        self._inv_prompting_qty = True
        try:
            value, ok = QtWidgets.QInputDialog.getInt(self, title, label, 1, 1, 1_000_000, 1)
        finally:
            self._inv_prompting_qty = False
        if not ok:
            return None
        return int(value)

    def _process_inventory_barcode(self, digits: str):
        """Добавить строку инвентаря или обновить количество."""
        try:
            article = barcode_to_article(digits)
        except Exception as e:
            self.set_status(f"Ошибка: {e}", error=True)
            self._clear_inv_scan()
            return

        qty = self._ask_inventory_qty(digits, article)
        if qty is None:
            self.set_status("Отменено (инвентарь)", error=True)
            self._clear_inv_scan()
            return

        idx = self.find_inv_row_index(digits)
        if idx is not None:
            before = self.inv_rows[idx].qty
            # При повторном сканировании всегда плюсую введённое кол-во.
            self.inv_rows[idx].qty += qty
            it = self.inv_table.item(idx, 2)
            if it is not None:
                it.setText(str(self.inv_rows[idx].qty))
            else:
                self.inv_table.setItem(idx, 2, QtWidgets.QTableWidgetItem(str(self.inv_rows[idx].qty)))
            self.set_status(
                f"Инвентарь обновлён: {digits} | {article} | +{qty} (итого {before + qty})"
            )

            self._clear_inv_scan()
            return

        self.inv_rows.append(InventoryRow(barcode=digits, article=article, qty=qty))
        r = self.inv_table.rowCount()
        self.inv_table.insertRow(r)
        self.inv_table.setItem(r, 0, QtWidgets.QTableWidgetItem(digits))
        self.inv_table.setItem(r, 1, QtWidgets.QTableWidgetItem(article))
        self.inv_table.setItem(r, 2, QtWidgets.QTableWidgetItem(str(qty)))
        self.inv_table.scrollToBottom()

        self.set_status(f"Добавлено в инвентарь: {digits} | {article} | {qty}")
        self._clear_inv_scan()

    def delete_selected_inventory(self):
        sel = self.inv_table.selectionModel().selectedRows()
        if not sel:
            self.set_status("Нечего удалять: строка инвентаря не выбрана", error=True)
            return
        for idx in sorted((s.row() for s in sel), reverse=True):
            self.inv_table.removeRow(idx)
            del self.inv_rows[idx]
        self.set_status("Удалено (инвентарь)")

    def clear_all_inventory(self):
        self.inv_table.setRowCount(0)
        self.inv_rows.clear()
        self.set_status("Очищено (инвентарь)")

    def default_inventory_path_for(self, ext: str) -> Path:
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        return Path.home() / "Desktop" / f"inventory_{ts}.{ext}"

    def save_inventory(self, kind: str):
        if not self.inv_rows:
            self.set_status("Пусто: нечего сохранять (инвентарь)", error=True)
            return

        if self.inv_save_path is None:
            self.inv_save_path = self.default_inventory_path_for("xlsx" if kind == "xlsx" else "csv")
            self.inv_path_label.setText(str(self.inv_save_path))

        out = self.inv_save_path
        if kind == "csv" and out.suffix.lower() != ".csv":
            out = out.with_suffix(".csv")
        if kind == "xlsx" and out.suffix.lower() != ".xlsx":
            out = out.with_suffix(".xlsx")

        df = pd.DataFrame(
            [{"barcode": r.barcode, "article": r.article, "qty": r.qty} for r in self.inv_rows]
        )

        try:
            if kind == "csv":
                df.to_csv(out, index=False, encoding="utf-8-sig")
            else:
                df.to_excel(out, index=False)
        except Exception as e:
            self.set_status(f"Ошибка сохранения инвентаря: {e}", error=True)
            return

        self.set_status(f"Сохранено (инвентарь): {out}")


def main():
    app = QtWidgets.QApplication()
    w = MainWindow()
    w.show()
    app.exec()


if __name__ == "__main__":
    main()
