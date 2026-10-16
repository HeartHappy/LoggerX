package com.hearthappy.loggerx

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import androidx.appcompat.app.AlertDialog
import com.hearthappy.loggerx.app.R
import com.hjq.permissions.IPermissionInterceptor
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import kotlin.properties.Delegates


/**
 * @Author ChenRui
 * @Email 1096885636@qq.com
 * @Date 2023/8/22
 * @Describe 权限管理类
 */
open class PermissionManager {

    protected var ctx: Context by Delegates.notNull()


    /**
     * 检查权限是否已经授权
     */
    fun checkPermission(permission: Int): Boolean {
        return when (permission) {
            STORAGE    -> { //适配Android11以上获取存储权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    XXPermissions.isGranted(ctx, Permission.MANAGE_EXTERNAL_STORAGE)
                } else {
                    XXPermissions.isGranted(ctx, Permission.READ_EXTERNAL_STORAGE)
                }
            }

            CALL_PHONE -> XXPermissions.isGranted(ctx, Permission.CALL_PHONE)

            LOCATION   -> XXPermissions.isGranted(ctx, Permission.ACCESS_FINE_LOCATION, Permission.ACCESS_COARSE_LOCATION)

            CAMERA     -> XXPermissions.isGranted(ctx, Permission.CAMERA)

            else       -> false
        }
    }


    /**
     * 申请权限前，提示权限用来做什么
     */
    fun applicationPrompt(msg: String, block: () -> Unit) {
        val builder = AlertDialog.Builder(ctx)

        builder.setTitle(ctx.resources.getString((R.string.app_name)) + '"' + "正在申请权限").setCancelable(false).setMessage(msg).setPositiveButton("确定", object : DialogInterface.OnClickListener {
                override fun onClick(dialog: DialogInterface?, which: Int) {
                    dialog?.dismiss()
                    if (ctx is Activity) {
                        if ((ctx as Activity).isFinishing) {
                            return
                        }
                        block()
                    }
                }
            }).setNegativeButton("取消", null)
        builder.create().show()
    }

    /**
     * 向系统申请权限
     */
    fun permission(callback: OnPermissionCallback,vararg permissions: Int) {
        val with: XXPermissions = XXPermissions.with(ctx)
        for (permission in permissions) {
            when (permission) {
                STORAGE          -> { //适配Android11以上获取存储权限
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        with.permission(Permission.MANAGE_EXTERNAL_STORAGE)
                        with.interceptor(object : IPermissionInterceptor {})
                    } else {
                        with.permission(Permission.READ_EXTERNAL_STORAGE)
                    }
                }

                CALL_PHONE       -> with.permission(Permission.CALL_PHONE)

                LOCATION         -> with.permission(Permission.ACCESS_FINE_LOCATION).permission(Permission.ACCESS_COARSE_LOCATION)

                CAMERA           -> with.permission(Permission.CAMERA)

                READ_PHONE_STATE -> with.permission(Permission.READ_PHONE_STATE)

                else             -> {}
            }
        }
        with.request(callback)
    }


    companion object : PermissionManager() {

        const val STORAGE: Int = 0
        const val CALL_PHONE: Int = 1
        const val LOCATION: Int = 2
        const val CAMERA: Int = 3
        const val READ_PHONE_STATE: Int = 4
        fun with(context: Context): PermissionManager {
            this.ctx = context
            return this
        }

        /**
         * 权限被永久拒绝，跳转至相关权限界面手动赋予
         */
        fun permanentlyRefuseToJump(permissions: List<String>) {
            XXPermissions.startPermissionActivity(ctx, permissions)
        }
    }
}
