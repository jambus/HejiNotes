package com.jambus.heji

import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.widget.ListAdapter
import android.database.Cursor
import android.app.AlertDialog

/** Ensures every standard dialog label is rendered for the currently selected app locale. */
class LocalizedAlertDialogBuilder(private val localizedContext: Context) : AlertDialog.Builder(localizedContext) {
    private fun label(value: CharSequence?): CharSequence? = value?.let { UiText.label(localizedContext, it.toString()) }
    override fun setTitle(title: CharSequence?): AlertDialog.Builder = super.setTitle(label(title))
    override fun setMessage(message: CharSequence?): AlertDialog.Builder = super.setMessage(label(message))
    override fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setPositiveButton(label(text), listener)
    override fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setNegativeButton(label(text), listener)
    override fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setNeutralButton(label(text), listener)
    override fun setItems(items: Array<out CharSequence>?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setItems(items?.map { label(it) ?: "" }.orEmpty().toTypedArray(), listener)
}
