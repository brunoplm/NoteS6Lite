package com.notes6lite.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.notes6lite.app.data.local.dao.FolderDao
import com.notes6lite.app.data.local.dao.NotebookDao
import com.notes6lite.app.data.local.dao.PageDao
import com.notes6lite.app.data.local.dao.StrokeDao
import com.notes6lite.app.data.local.entity.FolderEntity
import com.notes6lite.app.data.local.entity.NotebookEntity
import com.notes6lite.app.data.local.entity.PageEntity
import com.notes6lite.app.data.local.entity.StrokeEntity

@Database(
    entities = [
        NotebookEntity::class,
        PageEntity::class,
        StrokeEntity::class,
        FolderEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class NoteDatabase : RoomDatabase() {

    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao
    abstract fun strokeDao(): StrokeDao
    abstract fun folderDao(): FolderDao

    companion object {
        private const val DATABASE_NAME = "notes6lite.db"

        /**
         * v1 -> v2: biblioteca de pastas.
         *
         * - cria a tabela `folders` (auto-referenciada);
         * - adiciona `notebooks.folderId` (nulo = raiz), preservando as notas
         *   que o usuário já tinha.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folders` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`parentFolderId` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`parentFolderId`) REFERENCES `folders`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_folders_parentFolderId` " +
                        "ON `folders` (`parentFolderId`)"
                )
                db.execSQL("ALTER TABLE `notebooks` ADD COLUMN `folderId` INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notebooks_folderId` " +
                        "ON `notebooks` (`folderId`)"
                )
            }
        }

        @Volatile
        private var instance: NoteDatabase? = null

        fun getInstance(context: Context): NoteDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NoteDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
