package com.mishiranu.dashchan.ui;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Pair;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AutoCompleteTextView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import chan.content.ChanConfiguration;
import chan.util.StringUtils;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.FileProvider;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.service.DownloadService;
import com.mishiranu.dashchan.util.ConfigurationLock;
import com.mishiranu.dashchan.util.MimeTypes;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ToastUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class DownloadDialog {
	public interface Callback {
		void resolve(DownloadService.ChoiceRequest choiceRequest, DownloadService.DirectRequest directRequest);
		void resolve(DownloadService.ReplaceRequest replaceRequest, DownloadService.ReplaceRequest.Action action);
	}

	private final Context context;
	private final ConfigurationLock configurationLock;
	private final Callback callback;

	private Pair<AlertDialog, DownloadService.ChoiceRequest> choiceDialog;
	private Pair<AlertDialog, DownloadService.ReplaceRequest> replaceDialog;

	public DownloadDialog(Context context, ConfigurationLock configurationLock, Callback callback) {
		this.context = new ContextThemeWrapper(context, R.style.Theme_Gallery);
		this.configurationLock = configurationLock;
		this.callback = callback;
	}

	public void handleRequests(DownloadService.ChoiceRequest choiceRequest,
			DownloadService.ReplaceRequest replaceRequest) {
		if (choiceRequest != null) {
			if (replaceDialog != null) {
				replaceDialog.first.dismiss();
				replaceDialog = null;
			}
			if (choiceDialog != null && choiceDialog.second != choiceRequest) {
				choiceDialog.first.dismiss();
				choiceDialog = null;
			}
			if (choiceDialog == null) {
				choiceDialog = new Pair<>(createChoice(choiceRequest, dialog -> {
					if (choiceDialog != null && dialog == choiceDialog.first) {
						choiceDialog = null;
					}
				}), choiceRequest);
			}
		} else if (replaceRequest != null) {
			if (choiceDialog != null) {
				choiceDialog.first.dismiss();
				choiceDialog = null;
			}
			if (replaceDialog != null && replaceDialog.second != replaceRequest) {
				replaceDialog.first.dismiss();
				replaceDialog = null;
			}
			if (replaceDialog == null) {
				replaceDialog = new Pair<>(createReplace(replaceRequest, dialog -> {
					if (replaceDialog != null && dialog == replaceDialog.first) {
						replaceDialog = null;
					}
				}), replaceRequest);
			}
		} else {
			if (choiceDialog != null) {
				choiceDialog.first.dismiss();
				choiceDialog = null;
			}
			if (replaceDialog != null) {
				replaceDialog.first.dismiss();
				replaceDialog = null;
			}
		}
	}

	private AlertDialog createChoice(DownloadService.ChoiceRequest choiceRequest,
			AlertDialog.OnDismissListener onDismissListener) {
		File root = Preferences.getDownloadDirectory();
		InputMethodManager inputMethodManager = (InputMethodManager) context
				.getSystemService(Context.INPUT_METHOD_SERVICE);
		View view = LayoutInflater.from(context)
				.inflate(R.layout.dialog_download_choice, null);

		boolean allowDetailName = choiceRequest.allowDetailName();
		boolean allowOriginalName = choiceRequest.allowOriginalName();
		CheckBox detailNameCheckBox = view.findViewById(R.id.download_detail_name);
		CheckBox originalNameCheckBox = view.findViewById(R.id.download_original_name);
		if (choiceRequest.chanName == null && choiceRequest.boardName == null
				&& choiceRequest.threadNumber == null) {
			allowDetailName = false;
		}
		if (allowDetailName) {
			detailNameCheckBox.setChecked(Preferences.isDownloadDetailName());
		} else {
			detailNameCheckBox.setVisibility(View.GONE);
		}
		if (allowOriginalName) {
			originalNameCheckBox.setChecked(Preferences.isDownloadOriginalName());
		} else {
			originalNameCheckBox.setVisibility(View.GONE);
		}

		AutoCompleteTextView editText = view.findViewById(android.R.id.text1);
		if (!allowDetailName && !allowOriginalName) {
			((ViewGroup.MarginLayoutParams) editText.getLayoutParams()).topMargin = 0;
		}

		if (choiceRequest.chanName != null && choiceRequest.threadNumber != null) {
			String chanTitle = ChanConfiguration.get(choiceRequest.chanName).getTitle();
			String threadTitle = choiceRequest.threadTitle;
			if (threadTitle != null) {
				threadTitle = StringUtils.escapeFile(StringUtils.cutIfLongerToLine(threadTitle, 50, false), false);
			}
			String text = Preferences.getSubdir(choiceRequest.chanName, chanTitle,
					choiceRequest.boardName, choiceRequest.threadNumber, threadTitle);
			editText.setText(text);
			editText.setSelection(text.length());
			if (StringUtils.isEmpty(text)) {
				text = Preferences.formatSubdir(Preferences.DEFAULT_SUBDIR_PATTERN, choiceRequest.chanName,
						chanTitle, choiceRequest.boardName, choiceRequest.threadNumber, threadTitle);
			}
			editText.setHint(text);
		}

		editText.setEnabled(false);
		editText.setOnItemClickListener((parent, v, position, id) -> v.post(() -> {
			refreshDropDownContents(editText);
			editText.showDropDown();
		}));
		Runnable dropDownRunnable = editText::showDropDown;

		RadioGroup radioGroup = view.findViewById(R.id.download_choice);
		radioGroup.check(R.id.download_common);
		radioGroup.setOnCheckedChangeListener((rg, checkedId) -> {
			boolean enabled = checkedId == R.id.download_subdirectory;
			editText.setEnabled(enabled);
			editText.setCompoundDrawables(null, null, enabled
					? ResourceUtils.getDrawable(context, R.attr.buttonCancel, 0) : null, null);
			if (enabled) {
				editText.dismissDropDown();
				refreshDropDownContents(editText);
				if (inputMethodManager != null) {
					inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
					editText.postDelayed(dropDownRunnable, 250);
				} else {
					dropDownRunnable.run();
				}
			} else {
				editText.removeCallbacks(dropDownRunnable);
			}
		});
		view.<RadioButton>findViewById(R.id.download_common)
				.setText(context.getString(R.string.text_download_to_format, root.getName()));

		Adapter adapter = new Adapter(root, () -> {
			if (editText.isEnabled()) {
				refreshDropDownContents(editText);
			}
		});
		editText.setAdapter(adapter);

		AlertDialog dialog = new AlertDialog.Builder(context)
				.setTitle(R.string.text_download_title)
				.setView(view)
				.setNegativeButton(android.R.string.cancel, (d, w) -> callback.resolve(choiceRequest, null))
				.setPositiveButton(android.R.string.ok, (d, w) -> handleChoiceResolve(choiceRequest,
						editText, detailNameCheckBox, originalNameCheckBox))
				.setOnCancelListener(d -> callback.resolve(choiceRequest, null))
				.create();
		dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
				| WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
		editText.setOnEditorActionListener((v, actionId, event) -> {
			handleChoiceResolve(choiceRequest, editText, detailNameCheckBox, originalNameCheckBox);
			dialog.dismiss();
			return true;
		});
		configurationLock.lockConfiguration(dialog, dismissDialog -> {
			adapter.shutdown();
			onDismissListener.onDismiss(dismissDialog);
		});
		dialog.show();
		return dialog;
	}

	private void refreshDropDownContents(AutoCompleteTextView editText) {
		Editable editable = editText.getEditableText();
		TextWatcher[] watchers = editable.getSpans(0, editable.length(), TextWatcher.class);
		if (watchers != null) {
			for (TextWatcher watcher : watchers) {
				watcher.beforeTextChanged(editable, 0, 0, 0);
				watcher.onTextChanged(editable, 0, 0, 0);
				watcher.afterTextChanged(editable);
			}
		}
	}

	private void handleChoiceResolve(DownloadService.ChoiceRequest choiceRequest,
			AutoCompleteTextView editText, CheckBox detailNameCheckBox, CheckBox originalNameCheckBox) {
		String path = editText.isEnabled() ? StringUtils.nullIfEmpty(StringUtils
				.escapeFile(editText.getText().toString(), true).trim()) : null;
		DownloadService.DirectRequest directRequest = choiceRequest.complete(path,
				detailNameCheckBox.isChecked(), originalNameCheckBox.isChecked());
		callback.resolve(choiceRequest, directRequest);
	}

	private AlertDialog createReplace(DownloadService.ReplaceRequest replaceRequest,
			AlertDialog.OnDismissListener onDismissListener) {
		int count = replaceRequest.queued + replaceRequest.exists;
		float density = ResourceUtils.obtainDensity(context);
		int padding = context.getResources().getDimensionPixelSize(R.dimen.dialog_padding_view);
		LinearLayout linearLayout = new LinearLayout(context);
		linearLayout.setOrientation(LinearLayout.VERTICAL);
		linearLayout.setPadding(padding, padding, padding, C.API_LOLLIPOP ? (int) (8f * density) : padding);
		TextView textView = new TextView(context, null, android.R.attr.textAppearanceListItem);
		textView.setText(context.getResources().getQuantityString(R.plurals.text_files_exist_format, count, count));
		linearLayout.addView(textView);

		final RadioGroup radioGroup = new RadioGroup(context);
		radioGroup.setOrientation(RadioGroup.VERTICAL);
		int[] options = {R.string.action_replace, R.string.action_keep_all, R.string.action_skip};
		int[] ids = {android.R.id.button1, android.R.id.button2, android.R.id.button3};
		for (int i = 0; i < options.length; i++) {
			RadioButton radioButton = new RadioButton(context);
			radioButton.setText(options[i]);
			radioButton.setId(ids[i]);
			radioGroup.addView(radioButton);
		}
		radioGroup.check(ids[0]);
		radioGroup.setPadding(0, (int) (12f * density), 0, 0);
		linearLayout.addView(radioGroup);

		AlertDialog.Builder builder = new AlertDialog.Builder(context)
				.setView(linearLayout)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					switch (radioGroup.getCheckedRadioButtonId()) {
						case android.R.id.button1: {
							callback.resolve(replaceRequest, DownloadService.ReplaceRequest.Action.REPLACE);
							break;
						}
						case android.R.id.button2: {
							callback.resolve(replaceRequest, DownloadService.ReplaceRequest.Action.KEEP_ALL);
							break;
						}
						case android.R.id.button3: {
							callback.resolve(replaceRequest, DownloadService.ReplaceRequest.Action.SKIP);
							break;
						}
					}
				})
				.setNegativeButton(android.R.string.cancel, (d, w) -> callback.resolve(replaceRequest, null))
				.setOnCancelListener(d -> callback.resolve(replaceRequest, null));

		AlertDialog dialog;
		if (replaceRequest.exists == 1) {
			builder.setNeutralButton(R.string.action_view, null);
			dialog = builder.create();
			final File singleFile = replaceRequest.lastExistingFile;
			dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
				String extension = StringUtils.getFileExtension(singleFile.getPath());
				String type = MimeTypes.forExtension(extension, "image/jpeg");
				try {
					Uri uri = FileProvider.convertDownloadsFile(singleFile, type);
					int intentFlags = FileProvider.getIntentFlags();
					context.startActivity(new Intent(Intent.ACTION_VIEW)
							.setDataAndType(uri, type).setFlags(intentFlags | Intent.FLAG_ACTIVITY_NEW_TASK));
				} catch (ActivityNotFoundException e) {
					ToastUtils.show(context, R.string.message_unknown_address);
				}
			}));
		} else {
			dialog = builder.create();
		}
		configurationLock.lockConfiguration(dialog, onDismissListener);
		dialog.show();
		return dialog;
	}

	private static class Adapter extends BaseAdapter implements Filterable {
		private final File root;
		private final Runnable refresh;

		private List<DialogDirectory> items = Collections.emptyList();

		public Adapter(File root, Runnable refresh) {
			this.root = root;
			this.refresh = refresh;
		}

		@Override
		public int getCount() {
			return items.size();
		}

		@Override
		public DialogDirectory getItem(int position) {
			return items.get(position);
		}

		@Override
		public long getItemId(int position) {
			return 0;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			DialogDirectory dialogDirectory = getItem(position);
			if (convertView == null) {
				convertView = LayoutInflater.from(parent.getContext()).inflate(android.R.layout
						.simple_spinner_dropdown_item, parent, false);
				((TextView) convertView).setEllipsize(TextUtils.TruncateAt.START);
			}
			((TextView) convertView).setText(dialogDirectory.getDisplayName());
			return convertView;
		}

		private final Object lastDirectoryLock = new Object();
		private boolean lastDirectoryCancel = false;
		private String lastDirectoryPath;
		private List<DialogDirectory> lastDirectoryItems;
		private AsyncTask<Void, Void, List<DialogDirectory>> lastDirectoryTask;

		private final Filter filter = new Filter() {
			@Override
			protected FilterResults performFiltering(CharSequence constraint) {
				String constraintString = constraint.toString();
				int separatorIndex = constraintString.lastIndexOf('/');
				String directoryPath = separatorIndex >= 0 ? constraintString.substring(0, separatorIndex) : "";
				File directory = StringUtils.isEmpty(directoryPath) ? root : new File(root, directoryPath);

				List<DialogDirectory> items;
				synchronized (lastDirectoryLock) {
					if (lastDirectoryPath == null || !StringUtils.equals(lastDirectoryPath, directoryPath)) {
						lastDirectoryPath = directoryPath;
						lastDirectoryItems = Collections.emptyList();

						if (lastDirectoryTask != null) {
							lastDirectoryTask.cancel(true);
							lastDirectoryTask = null;
						}

						if (!lastDirectoryCancel) {
							lastDirectoryTask = new AsyncTask<Void, Void, List<DialogDirectory>>() {
								@Override
								protected List<DialogDirectory> doInBackground(Void... params) {
									ArrayList<DialogDirectory> items = new ArrayList<>();
									File[] files = directory.listFiles();
									if (files != null) {
										for (File file : files) {
											if (isCancelled()) {
												break;
											}
											if (file.isDirectory()) {
												items.add(new DialogDirectory(root, file));
											}
										}
									}
									if (!isCancelled()) {
										Collections.sort(items);
									}
									return items;
								}

								@Override
								protected void onPostExecute(List<DialogDirectory> items) {
									synchronized (lastDirectoryLock) {
										lastDirectoryItems = items;
										lastDirectoryTask = null;
										notifyDataSetChanged();
										refresh.run();
									}
								}
							};
							lastDirectoryTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
						}
					}

					items = lastDirectoryItems;
				}

				String name = constraintString.substring(separatorIndex + 1);
				ArrayList<DialogDirectory> result = new ArrayList<>();
				for (DialogDirectory item : items) {
					if (item.filter(name)) {
						result.add(item);
					}
				}

				FilterResults results = new FilterResults();
				results.values = result;
				results.count = result.size();
				return results;
			}

			@Override
			protected void publishResults(CharSequence constraint, FilterResults results) {
				@SuppressWarnings("unchecked")
				ArrayList<DialogDirectory> items = (ArrayList<DialogDirectory>) results.values;
				Adapter.this.items = items;
				notifyDataSetChanged();
			}
		};

		@Override
		public Filter getFilter() {
			return filter;
		}

		public void shutdown() {
			synchronized (lastDirectoryLock) {
				lastDirectoryCancel = true;
				if (lastDirectoryTask != null) {
					lastDirectoryTask.cancel(true);
					lastDirectoryTask = null;
				}
			}
		}
	}

	private static class DialogDirectory implements Comparable<DialogDirectory> {
		public final List<String> segments;
		public long lastModified;

		public DialogDirectory(File root, File directory) {
			String rootPath = root.getAbsolutePath();
			String directoryPath = directory.getAbsolutePath();
			String relativePath = directoryPath.substring(rootPath.length());
			if (relativePath.startsWith("/")) {
				relativePath = relativePath.substring(1);
			}
			segments = Arrays.asList(relativePath.split("/"));
			this.lastModified = directory.lastModified();
		}

		public boolean filter(String name) {
			Locale locale = Locale.getDefault();
			name = name.toLowerCase(locale);
			String lastSegment = segments.get(segments.size() - 1).toLowerCase(locale);
			if (lastSegment.startsWith(name)) {
				return true;
			}
			String[] splitted = lastSegment.split("[\\W_]+");
			for (String part : splitted) {
				if (part.startsWith(name)) {
					return true;
				}
			}
			return false;
		}

		private String convert(boolean displayName) {
			StringBuilder builder = new StringBuilder();
			for (String segment : segments) {
				if (builder.length() > 0) {
					if (displayName) {
						builder.append(" / ");
					} else {
						builder.append('/');
					}
				}
				builder.append(segment);
			}
			if (!displayName) {
				builder.append('/');
			}
			return builder.toString();
		}

		public String getDisplayName() {
			return convert(true);
		}

		@Override
		public String toString() {
			return convert(false);
		}

		@Override
		public int compareTo(DialogDirectory another) {
			return ((Long) another.lastModified).compareTo(lastModified);
		}
	}
}