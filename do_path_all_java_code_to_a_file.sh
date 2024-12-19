#!/bin/bash

# 检查是否提供了目录作为参数
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <directory>"
    exit 1
fi

# 指定的目录
DIR=$1

# 检查目录是否存在
if [ ! -d "$DIR" ]; then
    echo "Directory does not exist: $DIR"
    exit 1
fi

# 指定输出文件
OUTPUT_FILE="java_files_compiled.txt"

# 清空输出文件，准备写入
#> "$OUTPUT_FILE"

# 遍历目录下的所有.java文件
find "$DIR" -type f -name "*.java" | while read -r file; do
    # 将文件路径和名称添加到输出文件
    echo "File: $file" >> "$OUTPUT_FILE"

    # 将文件内容添加到输出文件
    cat "$file" >> "$OUTPUT_FILE"

    # 添加分隔线，以便区分不同的文件内容
    echo "------------------------------------" >> "$OUTPUT_FILE"
done

echo "Compilation complete. Output file: $OUTPUT_FILE"