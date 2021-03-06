package com.activeandroid;

/*
 * Copyright (C) 2010 Michael Pardo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.activeandroid.annotation.ForeignKey;
import com.activeandroid.annotation.PrimaryKey;
import com.activeandroid.content.ContentProvider;
import com.activeandroid.query.Delete;
import com.activeandroid.query.Select;
import com.activeandroid.serializer.TypeSerializer;
import com.activeandroid.util.AALog;
import com.activeandroid.util.ReflectionUtils;
import com.activeandroid.util.SQLiteUtils;

import java.lang.reflect.Field;
import java.util.List;

@SuppressWarnings("unchecked")
public abstract class Model implements IModel{
	//////////////////////////////////////////////////////////////////////////////////////
	// PRIVATE MEMBERS
	//////////////////////////////////////////////////////////////////////////////////////

	private TableInfo mTableInfo;

	//////////////////////////////////////////////////////////////////////////////////////
	// CONSTRUCTORS
	//////////////////////////////////////////////////////////////////////////////////////

	public Model() {
		mTableInfo = Cache.getTableInfo(getClass());
	}

    private long mId;

	//////////////////////////////////////////////////////////////////////////////////////
	// PUBLIC METHODS
	//////////////////////////////////////////////////////////////////////////////////////

    /**
     * Use This method to return the values of your primary key, must be separated by comma delimiter in order of declaration
     * Also each object thats instance of {@link java.lang.Number} must be DataBaseUtils.sqlEscapeString(object.toString)
     * @return
     */
	public abstract String getId();

	public final void delete() {
		Cache.openDatabase().delete(mTableInfo.getTableName(), SQLiteUtils.getWhereStatement(this, mTableInfo), null);
		Cache.removeEntity(this);

		Cache.getContext().getContentResolver()
				.notifyChange(ContentProvider.createUri(mTableInfo.getType(), getId()), null);
	}

	public final void save() {
		final SQLiteDatabase db = Cache.openDatabase();
		final ContentValues values = new ContentValues();

		for (Field field : mTableInfo.getFields()) {
			String fieldName = mTableInfo.getColumnName(field);
			Class<?> fieldType = field.getType();

			field.setAccessible(true);

			try {
				Object value = field.get(this);

				if (value != null) {
					final TypeSerializer typeSerializer = Cache.getParserForType(fieldType);
					if (typeSerializer != null) {
						// serialize data
						value = typeSerializer.serialize(value);
						// set new object type
						if (value != null) {
							fieldType = value.getClass();
							// check that the serializer returned what it promised
							if (!fieldType.equals(typeSerializer.getSerializedType())) {
								AALog.w(String.format("TypeSerializer returned wrong type: expected a %s but got a %s",
                                        typeSerializer.getSerializedType(), fieldType));
							}
						}
					}
				}

				// TODO: Find a smarter way to do this? This if block is necessary because we
				// can't know the type until runtime.
				if (value == null) {
					values.putNull(fieldName);
				}
				else if (fieldType.equals(Byte.class) || fieldType.equals(byte.class)) {
					values.put(fieldName, (Byte) value);
				}
				else if (fieldType.equals(Short.class) || fieldType.equals(short.class)) {
					values.put(fieldName, (Short) value);
				}
				else if (fieldType.equals(Integer.class) || fieldType.equals(int.class)) {
					values.put(fieldName, (Integer) value);
				}
				else if (fieldType.equals(Long.class) || fieldType.equals(long.class)) {
					values.put(fieldName, (Long) value);
				}
				else if (fieldType.equals(Float.class) || fieldType.equals(float.class)) {
					values.put(fieldName, (Float) value);
				}
				else if (fieldType.equals(Double.class) || fieldType.equals(double.class)) {
					values.put(fieldName, (Double) value);
				}
				else if (fieldType.equals(Boolean.class) || fieldType.equals(boolean.class)) {
					values.put(fieldName, (Boolean) value);
				}
				else if (fieldType.equals(Character.class) || fieldType.equals(char.class)) {
					values.put(fieldName, value.toString());
				}
				else if (fieldType.equals(String.class)) {
					values.put(fieldName, value.toString());
				}
				else if (fieldType.equals(Byte[].class) || fieldType.equals(byte[].class)) {
					values.put(fieldName, (byte[]) value);
				}
				else if (field.isAnnotationPresent(ForeignKey.class) && ReflectionUtils.isModel(fieldType)) {
                    ForeignKey key = field.getAnnotation(ForeignKey.class);
                    if(!key.name().equals("")){
                        fieldName = field.getAnnotation(ForeignKey.class).name();
                    }
					values.put(fieldName, ((Model) value).getId());
				}
				else if (ReflectionUtils.isSubclassOf(fieldType, Enum.class)) {
					values.put(fieldName, ((Enum<?>) value).name());
				}
			}
			catch (IllegalArgumentException e) {
				AALog.e(e.getClass().getName(), e);
			}
			catch (IllegalAccessException e) {
				AALog.e(e.getClass().getName(), e);
			}
		}

        if(!exists()){
		     mId = db.insert(mTableInfo.getTableName(), null, values);

            for(Field field : mTableInfo.getPrimaryKeys()){
                if(field.isAnnotationPresent(PrimaryKey.class) &&
                        field.getAnnotation(PrimaryKey.class).type().equals(PrimaryKey.Type.AUTO_INCREMENT)){
                    field.setAccessible(true);
                    try {
                        field.set(this, mId);
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        } else {
			mId = db.update(mTableInfo.getTableName(), values, SQLiteUtils.getWhereStatement(this, mTableInfo), null);
		}

		Cache.getContext().getContentResolver()
				.notifyChange(ContentProvider.createUri(mTableInfo.getType(), getId()), null);
	}

    public boolean exists(){
        Model model = new Select().from(getClass()).where(SQLiteUtils.getWhereStatement(this, mTableInfo)).executeSingle();
        return model!=null;
    }

    public void update(){

    }

    /**
     * Checks to see if object exists, if so, deletes it then updates itself
     */
    public <OBJECT_CLASS extends Model> void saveById(){
        if(exists()){
            delete();
        }
        save();
    }

	// Convenience methods

	public static void delete(Class<? extends Model> type, long id) {
		new Delete().from(type).where("Id=?", id).execute();
	}

	public static <T extends Model> T load(Class<T> type, long id) {
		return new Select().from(type).where("Id=?", id).executeSingle();
	}

	// Model population

	public final void loadFromCursor(Cursor cursor) {
		for (Field field : mTableInfo.getFields()) {
			final String fieldName = mTableInfo.getColumnName(field);
			Class<?> fieldType = field.getType();
			final int columnIndex = cursor.getColumnIndex(fieldName);

			if (columnIndex < 0) {
				continue;
			}

			field.setAccessible(true);

			try {
				boolean columnIsNull = cursor.isNull(columnIndex);
				TypeSerializer typeSerializer = Cache.getParserForType(fieldType);
				Object value = null;

				if (typeSerializer != null) {
					fieldType = typeSerializer.getSerializedType();
				}

				// TODO: Find a smarter way to do this? This if block is necessary because we
				// can't know the type until runtime.
				if (columnIsNull) {
					field = null;
				}
				else if (fieldType.equals(Byte.class) || fieldType.equals(byte.class)) {
					value = cursor.getInt(columnIndex);
				}
				else if (fieldType.equals(Short.class) || fieldType.equals(short.class)) {
					value = cursor.getInt(columnIndex);
				}
				else if (fieldType.equals(Integer.class) || fieldType.equals(int.class)) {
					value = cursor.getInt(columnIndex);
				}
				else if (fieldType.equals(Long.class) || fieldType.equals(long.class)) {
					value = cursor.getLong(columnIndex);
				}
				else if (fieldType.equals(Float.class) || fieldType.equals(float.class)) {
					value = cursor.getFloat(columnIndex);
				}
				else if (fieldType.equals(Double.class) || fieldType.equals(double.class)) {
					value = cursor.getDouble(columnIndex);
				}
				else if (fieldType.equals(Boolean.class) || fieldType.equals(boolean.class)) {
					value = cursor.getInt(columnIndex) != 0;
				}
				else if (fieldType.equals(Character.class) || fieldType.equals(char.class)) {
					value = cursor.getString(columnIndex).charAt(0);
				}
				else if (fieldType.equals(String.class)) {
					value = cursor.getString(columnIndex);
				}
				else if (fieldType.equals(Byte[].class) || fieldType.equals(byte[].class)) {
					value = cursor.getBlob(columnIndex);
				}
				else if (field.isAnnotationPresent(ForeignKey.class) && ReflectionUtils.isModel(fieldType)) {
					final String entityId = cursor.getString(columnIndex);
					final Class<? extends Model> entityType = (Class<? extends Model>) fieldType;

					IModel entity = Cache.getEntity(entityType, entityId);
					if (entity == null) {
						entity = new Select().from(entityType).where(SQLiteUtils.getWhereFromEntityId(entityType, entityId)).executeSingle();
					}

					value = entity;
				}
				else if (ReflectionUtils.isSubclassOf(fieldType, Enum.class)) {
					@SuppressWarnings("rawtypes")
					final Class<? extends Enum> enumType = (Class<? extends Enum>) fieldType;
					value = Enum.valueOf(enumType, cursor.getString(columnIndex));
				}

				// Use a deserializer if one is available
				if (typeSerializer != null && !columnIsNull) {
					value = typeSerializer.deserialize(value);
				}

				// Set the field name
				if (value != null) {
					field.set(this, value);
				}
			}
			catch (IllegalArgumentException e) {
				AALog.e(e.getClass().getName(), e);
			}
			catch (IllegalAccessException e) {
				AALog.e(e.getClass().getName(), e);
			}
			catch (SecurityException e) {
				AALog.e(e.getClass().getName(), e);
			}
		}

		if (getId() != null) {
			Cache.addEntity(this);
		}
	}

	//////////////////////////////////////////////////////////////////////////////////////
	// PROTECTED METHODS
	//////////////////////////////////////////////////////////////////////////////////////

    protected final <T extends Model> List<T> getManyFromField(Class<T> type,Object field, String foreignKey){
        return new Select().from(type).where(Cache.getTableName(type) + "." + foreignKey + "=?", field).execute();
    }

    protected final <T extends Model> List<T> getManyFromFieldWithSort(Class<T> type,Object field, String foreignKey, String sort){
        return new Select().from(type).orderBy(sort).where(Cache.getTableName(type) + "." + foreignKey + "=?", field).execute();
    }

	//////////////////////////////////////////////////////////////////////////////////////
	// OVERRIDEN METHODS
	//////////////////////////////////////////////////////////////////////////////////////

	@Override
	public String toString() {
		return mTableInfo!=null? mTableInfo.getTableName() + "@" + getId() : "No Table for: " + getClass() + "@" + getId();
	}

    public long getRowId(){
        return mId;
    }

    @Override
    public void setRowId(long id) {
        mId = id;
    }
}
