/*
 * Copyright (c) 2014 Jeff Martin
 * Copyright (c) 2015 Pedro Lafuente
 * Copyright (c) 2017 Gregor Santner and Markor contributors
 *
 * Licensed under the MIT license. See LICENSE file in the project root for details.
 */
package net.gsantner.markor.activity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.mobsandgeeks.adapters.Sectionizer;
import com.mobsandgeeks.adapters.SimpleSectionAdapter;

import net.gsantner.markor.App;
import net.gsantner.markor.R;
import net.gsantner.markor.adapter.NotesAdapter;
import net.gsantner.markor.dialog.ConfirmDialog;
import net.gsantner.markor.dialog.FilesystemDialogCreator;
import net.gsantner.markor.dialog.RenameDialog;
import net.gsantner.markor.model.Constants;
import net.gsantner.markor.model.MarkorSingleton;
import net.gsantner.markor.util.AppCast;
import net.gsantner.markor.util.AppSettings;
import net.gsantner.markor.util.ContextUtils;
import net.gsantner.opoc.ui.FilesystemDialogData;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnItemClick;

public class FilesystemListFragment extends Fragment {

    @BindView(R.id.filesystemlist__fragment__listview)
    public ListView _filesListView;

    @BindView(R.id.filesystemlist__fragment__background_hint_text)
    public TextView _background_hint_text;

    private NotesAdapter _filesAdapter;


    private ArrayList<File> _filesCurrentlyShown = new ArrayList<>();
    private ArrayList<File> _selectedItems = new ArrayList<>();
    private SimpleSectionAdapter<File> _simpleSectionAdapter;
    private MarkorSingleton _markorSingleton;
    private ActionMode _actionMode;
    private File _currentDir;
    private File _rootDir;
    private Sectionizer<File> _sectionizer = new Sectionizer<File>() {
        @Override
        public String getSectionTitleForItem(File instance) {
            return instance.isDirectory() ? getString(R.string.folders) : getString(R.string.files);
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ContextUtils.get().setAppLanguage(AppSettings.get().getLanguage());
        View root = inflater.inflate(R.layout.filesystemlist__fragment, container, false);
        ButterKnife.bind(this, root);
        return root;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context context = getActivity();
        _filesAdapter = new NotesAdapter(context, 0, _filesCurrentlyShown);
        _simpleSectionAdapter = new SimpleSectionAdapter<>(context, _filesAdapter,
                R.layout.ui__text__item,
                R.id.notes_fragment_section_text, _sectionizer);

        _filesListView.setMultiChoiceModeListener(new ActionModeCallback());
        _filesListView.setAdapter(_simpleSectionAdapter);
        _rootDir = getRootFolderFromPrefsOrDefault();
    }

    @Override
    public void onResume() {
        super.onResume();
        _markorSingleton = MarkorSingleton.getInstance();
        File possiblyNewRootDir = getRootFolderFromPrefsOrDefault();
        if (possiblyNewRootDir != _rootDir) {
            _rootDir = possiblyNewRootDir;
            _currentDir = possiblyNewRootDir;
        }
        retrieveCurrentFolder();
        listFilesInDirectory(getCurrentDir());
    }

    private File getRootFolderFromPrefsOrDefault() {
        return new File(AppSettings.get().getSaveDirectory());
    }

    @Override
    public void onPause() {
        super.onPause();
        saveCurrentFolder();
    }

    private void retrieveCurrentFolder() {
        AppSettings appSettings = AppSettings.get();
        String rememberedDir = appSettings.getLastOpenedDirectory();
        _currentDir = (rememberedDir != null) ? new File(rememberedDir) : null;
        // Two-fold check, in case user doesn't have the preference to remember directories enabled
        // This code remembers last directory WITHIN the app (not leaving it)
        if (_currentDir == null) {
            _currentDir = (_markorSingleton.getNotesLastDirectory() != null) ? _markorSingleton.getNotesLastDirectory() : _rootDir;
        }
    }

    private void saveCurrentFolder() {
        AppSettings appSettings = AppSettings.get();
        SharedPreferences pm = PreferenceManager.getDefaultSharedPreferences(getActivity().getApplicationContext());
        String saveDir = (_currentDir == null) ? _rootDir.getAbsolutePath() : _currentDir.getAbsolutePath();
        appSettings.setLastOpenedDirectory(saveDir);
        _markorSingleton.setNotesLastDirectory(_currentDir);
    }

    private void confirmDelete() {
        final ArrayList<File> itemsToDelete = new ArrayList<>(_selectedItems);
        ConfirmDialog confirmDialog = ConfirmDialog.newInstance(R.string.confirm_delete, itemsToDelete,
                new ConfirmDialog.ConfirmDialogCallback() {
                    @Override
                    public void onConfirmDialogAnswer(boolean confirmed, Serializable data) {
                        if (confirmed) {
                            MarkorSingleton.getInstance().deleteSelectedItems(itemsToDelete);
                            listFilesInDirectory(getCurrentDir());
                            finishActionMode();
                        }
                    }
                });
        confirmDialog.show(getFragmentManager(), ConfirmDialog.FRAGMENT_TAG);
    }

    private void promptForMoveDirectory() {
        final ArrayList<File> filesToMove = new ArrayList<>(_selectedItems);
        FilesystemDialogCreator.showFolderDialog(new FilesystemDialogData.SelectionListenerAdapter() {
            @Override
            public void onFsSelected(String request, File file) {
                super.onFsSelected(request, file);
                MarkorSingleton.getInstance().moveSelectedNotes(filesToMove, file.getAbsolutePath());
                listFilesInDirectory(getCurrentDir());
                finishActionMode();
            }

            @Override
            public void onFsDialogConfig(FilesystemDialogData.Options opt) {
                opt.titleText = R.string.select_folder;
                opt.rootFolder = new File(AppSettings.get().getSaveDirectory());
            }
        }, getActivity().getSupportFragmentManager(), getActivity());
    }


    public void listFilesInDirectory(File directory) {
        _selectedItems.clear();
        reloadFiles(directory);
        broadcastDirectoryChange(directory);
        showEmptyDirHintIfEmpty();
        reloadAdapter();
    }

    private void broadcastDirectoryChange(File directory) {
        AppCast.VIEW_FOLDER_CHANGED.send(getActivity(), directory.getAbsolutePath(), false);
        clearSearchFilter();
    }

    private void reloadFiles(File directory) {

        try {
            // Load from SD card
            _filesCurrentlyShown = MarkorSingleton.getInstance().addMarkdownFilesFromDirectory(directory, new ArrayList<File>());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void reloadAdapter() {
        if (_filesAdapter != null) {
            _filesAdapter = new NotesAdapter(getActivity().getApplicationContext(), 0, _filesCurrentlyShown);
            _simpleSectionAdapter =
                    new SimpleSectionAdapter<>(getActivity().getApplicationContext()
                            , _filesAdapter, R.layout.ui__text__item
                            , R.id.notes_fragment_section_text, _sectionizer);
            _filesListView.setAdapter(_simpleSectionAdapter);
            _simpleSectionAdapter.notifyDataSetChanged();
        }
    }

    public void goToPreviousDir() {
        if (_currentDir != null) {
            _currentDir = _currentDir.getParentFile();
        }

        listFilesInDirectory(getCurrentDir());
    }

    private void showEmptyDirHintIfEmpty() {
        if (_markorSingleton.isDirectoryEmpty(_filesCurrentlyShown)) {
            _background_hint_text.setVisibility(View.VISIBLE);
            _background_hint_text.setText(getString(R.string.empty_directory));
        } else {
            _background_hint_text.setVisibility(View.INVISIBLE);
        }
    }

    public File getCurrentDir() {
        return (_currentDir == null) ? getRootDir() : _currentDir.getAbsoluteFile();
    }

    public File getRootDir() {
        return _rootDir.getAbsoluteFile();
    }

    public void finishActionMode() {
        _actionMode.finish();
        _selectedItems.clear();
    }

    /**
     * Search
     **/
    public void search(CharSequence query) {
        if (query.length() > 0) {
            _filesAdapter.getFilter().filter(query);
            _simpleSectionAdapter.notifyDataSetChanged();
        }
    }

    public void clearSearchFilter() {
        _filesAdapter.getFilter().filter("");
        _simpleSectionAdapter.notifyDataSetChanged();
        reloadAdapter();
    }

    public void sortByName(){
        int size = _filesCurrentlyShown.size();
        int k=0;
        for(int i=0;i<size;i++){
            if(_filesCurrentlyShown.get(i).isDirectory()){
                k++;
            }
        }
        Collections.sort(_filesCurrentlyShown.subList(0, k), new Comparator<File>() {
            @Override
            public int compare(File file1, File file2) {
                return file1.compareTo(file2);
            }
        });
        Collections.sort(_filesCurrentlyShown.subList(k, size), new Comparator<File>() {
            @Override
            public int compare(File file1, File file2) {
                return file1.compareTo(file2);
            }
        });
        reloadAdapter();
    }

    public void sortByDate(){
        int size = _filesCurrentlyShown.size();
        int k=0;
        for(int i=0;i<size;i++){
            if(_filesCurrentlyShown.get(i).isDirectory()){
                k++;
            }
        }
        Collections.sort(_filesCurrentlyShown.subList(0, k), new Comparator<File>() {
            @Override
            public int compare(File file1, File file2) {
                return (int)(file2.lastModified()-file1.lastModified());
            }
        });
        Collections.sort(_filesCurrentlyShown.subList(k, size), new Comparator<File>() {
            @Override
            public int compare(File file1, File file2) {
                return (int)(file2.lastModified()-file1.lastModified());
            }
        });
        reloadAdapter();
    }

    public void sortBySize(){
        int size = _filesCurrentlyShown.size();
        int k=0;
        for(int i=0;i<size;i++){
            if(_filesCurrentlyShown.get(i).isDirectory()){
                k++;
            }
        }
        Collections.sort(_filesCurrentlyShown.subList(0, k), new Comparator<File>() {
            @Override
            public int compare(File file1, File file2) {
                return (int)(file1.getUsableSpace()-file2.getUsableSpace());
            }
        });
        Collections.sort((_filesCurrentlyShown.subList(k, size)), new Comparator<File>() {
            @Override
            public int compare(File file1, File file2) {
                return (int)(file1.getUsableSpace()-file2.getUsableSpace());
            }
        });
        reloadAdapter();
    }

    public ArrayList<File> getSelectedItems() {
        return _selectedItems;
    }

    public boolean onRooDir() {
        return _markorSingleton.isRootDir(_currentDir, _rootDir);
    }

    private class ActionModeCallback implements ListView.MultiChoiceModeListener {

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.notes_context_menu, menu);
            mode.setTitle(getResources().getString(R.string.select_elements));
            return true;
        }


        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            _actionMode = mode;
            switch (item.getItemId()) {
                case R.id.context_menu_delete:
                    confirmDelete();
                    finishActionMode();
                    return true;
                case R.id.context_menu_move:
                    promptForMoveDirectory();
                    finishActionMode();
                    return true;
                case R.id.context_menu_rename:
                    promptForNewName(_selectedItems.get(0));
                    finishActionMode();
                    return true;
                default:
                    return false;
            }
        }

        private void promptForNewName(File file) {
            RenameDialog renameDialog = RenameDialog.newInstance(file);
            renameDialog.show(getFragmentManager(), RenameDialog.FRAGMENT_TAG);
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
        }

        @Override
        public void onItemCheckedStateChanged(ActionMode actionMode, int i, long l, boolean checked) {

            switch (_filesListView.getCheckedItemCount()) {
                case 0:
                    actionMode.setSubtitle(null);
                    setRenameButtonVisibility(actionMode, false);
                    break;
                case 1:
                    actionMode.setSubtitle(getResources().getString(R.string.one_item_selected));
                    manageClickedVIew(i, checked);
                    setRenameButtonVisibility(actionMode, true);
                    break;
                default:
                    manageClickedVIew(i, checked);
                    actionMode.setSubtitle(String.format(getResources().getString(R.string.more_items_selected), _filesListView.getCheckedItemCount()));
                    setRenameButtonVisibility(actionMode, false);
                    break;
            }
        }

        private void manageClickedVIew(int i, boolean checked) {
            if (checked) {
                _selectedItems.add((File) _simpleSectionAdapter.getItem(i));
            } else {
                _selectedItems.remove((File) _simpleSectionAdapter.getItem(i));
            }
        }

        private void setRenameButtonVisibility(ActionMode actionMode, boolean visible) {
            Menu menu = actionMode.getMenu();
            MenuItem item = menu.findItem(R.id.context_menu_rename);
            item.setVisible(visible);
        }
    } // End: Action Mode callback

    @OnItemClick(R.id.filesystemlist__fragment__listview)
    public void onNotesItemClickListener(AdapterView<?> adapterView, View view, int i, long l) {
        File file = (File) _simpleSectionAdapter.getItem(i);
        Context context = view.getContext();

        // Refresh list if directory, else import
        if (file.isDirectory()) {
            _currentDir = file;
            listFilesInDirectory(file);
        } else {

            File note = (File) _simpleSectionAdapter.getItem(i);
            Intent intent;

            if (AppSettings.get().isPreviewFirst()) {
                intent = new Intent(context, PreviewActivity.class);

                if (note != null) {
                    Uri uriBase = null;
                    if (note.getParentFile() != null) {
                        uriBase = Uri.parse(note.getParentFile().toURI().toString());
                    }

                    intent.putExtra(Constants.MD_PREVIEW_BASE, uriBase.toString());
                }

                Uri noteUri = Uri.parse(note.toURI().toString());
                String content = MarkorSingleton.getInstance().readFileUri(noteUri, context);
                intent.putExtra(Constants.MD_PREVIEW_KEY, content);
            } else {
                intent = new Intent(context, NoteActivity.class);
            }
            intent.putExtra(Constants.NOTE_KEY, note);

            startActivity(intent);
        }
    }
}
