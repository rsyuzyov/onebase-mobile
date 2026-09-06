package ru.ontab.onebase

import android.app.Application

/**
 * Перехватчик падений ставится здесь, а не в экране: Application создаётся первым,
 * и в него попадают в том числе сбои, случившиеся до появления интерфейса, — именно
 * они выглядят у пользователя как «приложение не открывается».
 */
class OneBaseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
    }
}
