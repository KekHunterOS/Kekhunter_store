package com.team420.kekstore.views.updates.items;

import androidx.appcompat.app.AppCompatActivity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.snackbar.Snackbar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.view.View;

import com.team420.kekstore.AppUpdateStatusManager;
import com.team420.kekstore.R;
import com.team420.kekstore.data.Apk;
import com.team420.kekstore.data.ApkProvider;
import com.team420.kekstore.data.App;
import com.team420.kekstore.data.AppPrefs;
import com.team420.kekstore.data.AppPrefsProvider;
import com.team420.kekstore.data.AppProvider;
import com.team420.kekstore.installer.InstallManagerService;
import com.team420.kekstore.installer.Installer;
import com.team420.kekstore.installer.InstallerService;
import com.team420.kekstore.views.apps.AppListItemController;
import com.team420.kekstore.views.apps.AppListItemState;
import com.team420.kekstore.views.updates.UpdatesAdapter;

/**
 * Tell the user that an app they have installed has a known vulnerability.
 * The role of this controller is to prompt the user what it is that should be done in response to this
 * (e.g. uninstall, update, disable).
 */
public class KnownVulnAppListItemController extends AppListItemController {
    public KnownVulnAppListItemController(AppCompatActivity activity, View itemView) {
        super(activity, itemView);
    }

    @NonNull
    @Override
    protected AppListItemState getCurrentViewState(
            @NonNull App app, @Nullable AppUpdateStatusManager.AppUpdateStatus appStatus) {
        String mainText;
        String actionButtonText;

        Apk suggestedApk = ApkProvider.Helper.findSuggestedApk(activity, app);
        if (shouldUpgradeInsteadOfUninstall(app, suggestedApk)) {
            mainText = activity.getString(R.string.updates__app_with_known_vulnerability__prompt_upgrade, app.name);
            actionButtonText = activity.getString(R.string.menu_upgrade);
        } else {
            mainText = activity.getString(R.string.updates__app_with_known_vulnerability__prompt_uninstall, app.name);
            actionButtonText = activity.getString(R.string.menu_uninstall);
        }

        return new AppListItemState(app)
                .setMainText(mainText)
                .showActionButton(actionButtonText)
                .showSecondaryButton(activity.getString(R.string.updates__app_with_known_vulnerability__ignore));
    }

    private boolean shouldUpgradeInsteadOfUninstall(@NonNull App app, @Nullable Apk suggestedApk) {
        return suggestedApk != null && app.installedVersionCode < suggestedApk.versionCode;
    }

    @Override
    protected void onActionButtonPressed(@NonNull App app) {
        Apk installedApk = app.getInstalledApk(activity);
        if (installedApk == null) {
            throw new IllegalStateException(
                    "Tried to update or uninstall app with known vulnerability but it doesn't seem to be installed");
        }

        Apk suggestedApk = ApkProvider.Helper.findSuggestedApk(activity, app);
        if (shouldUpgradeInsteadOfUninstall(app, suggestedApk)) {
            LocalBroadcastManager manager = LocalBroadcastManager.getInstance(activity);
            manager.registerReceiver(installReceiver,
                    Installer.getInstallIntentFilter(suggestedApk.getCanonicalUrl()));
            InstallManagerService.queue(activity, app, suggestedApk);
        } else {
            LocalBroadcastManager manager = LocalBroadcastManager.getInstance(activity);
            manager.registerReceiver(installReceiver, Installer.getUninstallIntentFilter(app.packageName));
            InstallerService.uninstall(activity, installedApk);
        }
    }

    @Override
    public boolean canDismiss() {
        return true;
    }

    @Override
    protected void onDismissApp(@NonNull final App app, UpdatesAdapter adapter) {
        this.ignoreVulnerableApp(app);
    }

    @Override
    protected void onSecondaryButtonPressed(@NonNull App app) {
        this.ignoreVulnerableApp(app);
    }

    private void ignoreVulnerableApp(@NonNull final App app) {
        setIgnoreVulnerableApp(app, true);

        Snackbar.make(
                itemView,
                R.string.app_list__dismiss_vulnerable_app,
                Snackbar.LENGTH_LONG
        )
                .setAction(R.string.undo, new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        setIgnoreVulnerableApp(app, false);
                    }
                })
                .show();
    }

    private void setIgnoreVulnerableApp(@NonNull App app, boolean ignore) {
        AppPrefs prefs = app.getPrefs(activity);
        prefs.ignoreVulnerabilities = ignore;
        AppPrefsProvider.Helper.update(activity, app, prefs);
        refreshUpdatesList();
    }

    private void unregisterInstallReceiver() {
        LocalBroadcastManager.getInstance(activity).unregisterReceiver(installReceiver);
    }

    /**
     * Trigger the LoaderManager in UpdatesAdapter to automatically requery for the list of
     * apps with known vulnerabilities (i.e. this app should no longer be in that list).
     */
    private void refreshUpdatesList() {
        activity.getContentResolver().notifyChange(AppProvider.getInstalledWithKnownVulnsUri(), null);
    }

    private final BroadcastReceiver installReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Installer.ACTION_INSTALL_COMPLETE:
                case Installer.ACTION_UNINSTALL_COMPLETE:
                    refreshUpdatesList();
                    unregisterInstallReceiver();
                    break;

                case Installer.ACTION_INSTALL_INTERRUPTED:
                case Installer.ACTION_UNINSTALL_INTERRUPTED:
                    unregisterInstallReceiver();
                    break;

                case Installer.ACTION_INSTALL_USER_INTERACTION:
                case Installer.ACTION_UNINSTALL_USER_INTERACTION:
                    PendingIntent uninstallPendingIntent =
                            intent.getParcelableExtra(Installer.EXTRA_USER_INTERACTION_PI);

                    try {
                        uninstallPendingIntent.send();
                    } catch (PendingIntent.CanceledException ignored) {
                    }
                    break;
            }
        }
    };
}
